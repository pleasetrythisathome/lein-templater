(ns leiningen.templater
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :as w]
            [rewrite-clj.zip :as z]
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

(defn read-all
  "reads all lines from a file and returns a sequence of forms"
  [path]
  (let [r (java.io.PushbackReader.
           (clojure.java.io/reader path))]
    (let [eof (Object.)]
      (take-while #(not= % eof) (repeatedly #(read r false eof))))))

(defn spit-seq
  "spits all the items in a seq concatted into a file"
  [path forms]
  (doall (map-indexed (fn [idx form]
                        (spit path (with-out-str (pprint form)) :append (not (zero? idx))))
                      forms)))

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

(defn get-rel-path [file]
  (-> file
      .getAbsolutePath
      (str/replace (str (:root @project) "/") "")))

(defn is-overridden?
  "takes either keys or vals from file-overrides and returns a function that
returns true if the file has an override path defined in the template project settings"
  [f]
  (fn [file]
    (let [{:keys [root template]} @project]
      (contains? (set (f (:file-overrides template))) (get-rel-path file)))))

(defn get-project-files
  "returns all files in the project that are not ignored by git"
  []
  (let [{:keys [root template]} @project]
    (->> root
         io/file
         file-seq
         (filter (complement gitignored?))
         (filter (complement (is-overridden? vals)))
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

;; ===== file processors =====

(defn build-renderers [files root name]
  (map (juxt (fn [file]
               (-> file
                   .getAbsolutePath
                   (str/replace root "")
                   (replace-template-var (sanitize name) "sanitized")))
             (fn [file]
               (seq ['render (unhide (.getName file)) 'data])))
       files))

(defn modify-proj-map
  "converts a proj file into a map, applies f to it, and then prints it back to a string"
  [f proj-string]
  (let [proj (read-string proj-string)]
    (->> proj
         (drop 3)
         (apply hash-map)
         f
         (mapcat identity)
         (concat (take 3 proj))
         pr-str)))

(defn process-file [file]
  (if ((is-overridden? keys) file)
    (->> file
         get-rel-path
         (conj [:template :file-overrides])
         (get-in @project)
         io/file
         process-file)
    (-> file
        .getAbsolutePath
        slurp
        (cond->>
         (= "project.clj" (.getName file)) (modify-proj-map #(apply dissoc % [:template])))
        (replace-template-var (:name @project) "name"))))

(defn process-renderer-step
  [form]
  (let [{:keys [name root] :as project} @project]
    (cond
     (symbol-first? '->files form) (concat (butlast form) (build-renderers (get-project-files) root name))
     (symbol-first? 'main/info form) (concat (butlast form) [(get-in project [:template :msg])])
     :else (identity form))))

;; ===== let's make templates! =====

(defn templater []
  (let [{:keys [name root template] :as proj} @project
        {:keys [output-dir title]} template

        target-dir (str root "/" output-dir)
        lein-dir (str target-dir "/src/leiningen/new/")
        src-dir (str lein-dir title "/")
        renderer-path (str lein-dir title ".clj")]

    (fresh-template title target-dir)

    (let [renderer (->> renderer-path
                        read-all
                        (w/postwalk process-renderer-step))]
      (spit-seq renderer-path renderer))

    (doseq [file (get-project-files)]
      (spit (str src-dir (unhide (.getName file))) (process-file file)))))




(comment (templater)

         (def path (->> "/lein-template/src/leiningen/new/satori.clj"
                        (str (:root @project))))

         (with-out-str (->> path
                            read-all
                            (w/postwalk process-renderer-step)
                            pprint))

         (def data (z/of-string (->> path
                                     read-all
                                     pr-str)))

         (z/sexpr data)

         (pprint data)

         (-> data
             (z/find 'main/info)
             z/sexpr)

         (-> data
             z/down
             (z/find-value z/right 'defn)
             z/sexpr)

         (z/find-value data z/next '->files)
         )
