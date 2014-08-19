(ns leiningen.templater
  (:require [clojure.string :as str]
            [clojure.core.match :as m]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :as w]
            [rewrite-clj.zip :as z]
            [rewrite-clj.parser :as p]
            [rewrite-clj.printer :as prn])
  (:use [cfg.current]))

;; ===== general utils =====

(defn sanitize
  "Replace hyphens with underscores."
  [s]
  (str/replace s "-" "_"))

(defn symbol-first?
  "returns true if sym is the first item in seq"
  [sym seq]
  (and (seq? seq)
       (-> seq first
           (= sym))))

;; ===== file io =====

(defn gitignored?
  "returns true if the file would be ignored by git"
  [file]
  (not (str/blank? (->> file
                        .getAbsolutePath
                        (sh "git" "check-ignore")
                        :out))))

(defn unhide
  "removes a prefixed . if it exists"
  [filename]
  (cond-> filename
          (= \. (first filename)) (subs 1)))

(defn get-rel-path
  "returns the path of the file relative to the project root"
  [file]
  (-> file
      .getAbsolutePath
      (str/replace (str (:root @project) "/") "")))

(defn is-template-project?
  "returns true if the file is the project file for the new template"
  [file]
  (let [out (get-in @project [:template :output-dir])]
    (= (get-rel-path file) (str out "/project.clj"))))

(defn is-overridden?
  "takes either keys or vals from file-overrides and returns a function that
returns true if the file has an override path defined in the template project settings"
  [file loc]
  (let [{:keys [root template]} @project
        f (condp = loc
            :project vals
            :template keys)]
    (contains? (set (f (:file-overrides template))) (get-rel-path file))))

(defn get-override
  "returns the overwritten file defined in :template in project"
  [file]
  (->> file
       get-rel-path
       (conj [:template :file-overrides])
       (get-in @project)
       io/file))

(defn file-or-override
  "returns either the file or its defined override"
  [file]
  (cond-> file
          (is-overridden? file :template) get-override))

(defn get-project-files
  "returns all files in the project that are not ignored by git"
  []
  (let [{:keys [root template]} @project]
    (->> root
         io/file
         file-seq
         (filter (complement gitignored?))
         (filter #(not (is-overridden? % :project)))
         (filter #(.isFile %)))))

;; ===== lein utils =====

(defn replace-template-var
  "replace a matched string with a lein template variable"
  [s match var]
  (str/replace s match (str "{{" var "}}")))

(defn fresh-template [title dir]
  (sh "rm" "-rf" dir)
  (sh "lein" "new" "template" title "--to-dir" dir)
  (sh "rm" (str dir "/src/leiningen/new/" title "/foo.clj")))

;; ===== make core.match compatible with regex =====

(defrecord RegexPattern [regex])

(defmethod m/emit-pattern java.util.regex.Pattern
  [pat]
  (RegexPattern. pat))

(defmethod m/to-source RegexPattern
  [pat ocr]
  `(re-find ~(:regex pat) ~ocr))

;; ===== zipper utils =====

(defn append-children
  "appends a sequence of children to a zipper location"
  [zloc children]
  (reduce (fn [out item]
            (z/append-child out item))
          zloc
          children))

(defn zip-file-string
  "creates a zipper from a slurped file string. (wraps in seq)
  steps into zipper root
  applys f
  recreates zipper from root
  prints back to string"
  [file-string f]
  (-> file-string
      (#(str "(" % "\n)"))
      z/of-string
      f
      z/->root-string
      (#(subs % 1 (- (count %) 3)))))

(defn assoc-proj
  "assoc a key in a project file (or similar) with the supplied value"
  [root & kvs]
  (reduce-kv (fn [root key value]
               (-> root
                   z/down
                   (z/find-value z/next key)
                   z/right
                   (z/replace value)
                   z/up
                   z/up))
             root
             (apply hash-map kvs)))

(defn dissoc-proj
  "dissocs a key in a project file (or similar)"
  [root & keys]
  (reduce (fn [root key]
            (-> root
                z/down
                (z/find-value z/next key)
                z/remove
                z/next
                z/remove
                z/next
                z/up
                z/up))
          root
          keys))

;; ===== file processors =====

(defn build-renderers
  "builds a vector of file renderers"
  [files]
  (let [{:keys [root name] :as project} @project]
    (map (juxt (fn [file]
                 (-> file
                     .getAbsolutePath
                     (str/replace (str root "/") "")
                     (replace-template-var (sanitize name) "sanitized")))
               (fn [file]
                 (seq ['render (unhide (.getName file)) 'data])))
         files)))

(defn zip-renderer
  "builds the renderer file from the root zipper"
  [root]
  (-> root
      z/down
      (z/find-value z/next 'main/info)
      z/right
      (z/replace (get-in @project [:template :msg]))
      z/up
      (z/find-value z/next '->files)
      z/rightmost
      z/remove
      z/up
      (append-children (build-renderers (get-project-files)))))

(defn zip-template-proj
  "replaces template project values with those defined in :template in project.clj"
  [root]
  (let [{:keys [title version project]} (:template @project)]
    (-> root
       z/down
       (z/find-value z/next "0.1.0-SNAPSHOT")
       (z/replace version)
       z/up
       (#(apply assoc-proj % (flatten (seq project)))))))

#_(-> (str (:root @project) "/lein-template/project.clj")
    slurp
    (zip-file-string zip-template-proj)
    print)

(defn process-file
  "processes the file based on file type, name, and context"
  [file]
  (let [title (get-in @project [:template :title])
        [type & filename] (-> (.getName file)
                              (str/split #"\.")
                              reverse)
        filename (str/join "." (reverse filename))

        file-str (-> file
                     .getAbsolutePath
                     slurp)
        template #(replace-template-var % (:name @project) "name")]
    (m/match [filename type]
             ["project" "clj"] (if (is-template-project? file)
                                 (-> file-str
                                     (zip-file-string zip-template-proj))
                                 (-> file-str
                                     template
                                     (zip-file-string #(dissoc-proj % :template))))
             [filename #"clj"] (if (= title filename)
                                 (zip-file-string file-str zip-renderer)
                                 (template file-str))
             :else file-str)))

;; ===== let's make templates! =====

(defn templater
  "creates a new line template from the current project and the :template map in project.clj"
  []
  (let [{:keys [name root template] :as proj} @project
        {:keys [output-dir title readme]} template

        target-dir (str root "/" output-dir)
        lein-dir (str target-dir "/src/leiningen/new/")
        src-dir (str lein-dir title "/")

        path-file (juxt identity
                        io/file)
        replace-file (fn [render-path key]
                       [render-path (io/file (or (get template key) render-path))])]

    (fresh-template title target-dir)

    (doseq [[path file] (concat [(path-file (str lein-dir title ".clj"))
                                 (path-file (str target-dir "/project.clj"))
                                 (replace-file (str target-dir "/README.md") :readme)
                                 (replace-file (str target-dir "/LICENSE") :license)]
                                (mapv (juxt (fn [file]
                                              (->> file
                                                   .getName
                                                   unhide
                                                   (str src-dir)))
                                            file-or-override)
                                      (get-project-files)))]
      (spit path (process-file file)))))
