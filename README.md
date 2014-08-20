# lein-templater

A [Leiningen](https://github.com/technomancy/leiningen) for automatically generating a [Leiningen](https://github.com/technomancy/leiningen) from a previously existing project. Woah! That's some meta shit! Why didn't you think of a cooler name? Sorry, I figured templating was annoying enough already...

## Usage

Leiningen ([via Clojars](https://clojars.org/lein-templater))

Put one of the following into the ```:plugins``` vector of the ```:user``` profile in your ```~/.lein/profiles.clj```:

```
[lein-ancient "0.1.1-SNAPSHOT"]
```

## Make Templates

```lein templater``` will generate a new template that will render all of the files in your current project (excluding those that are gitignored) into their appropriate directories, with your project's name properly replaced.

lein-tempater looks for a an optional ```:template``` key in ```project.clj```.

Below is a list of understood keys

```clojure
{;; the template's title to be used with lein new. defaults to same as project
 :title "project-title"
 ;; the relative path where the template will be generated. defaults to lein-template
 :output-dir "lein-template"
 ;; the version number of your current template. defaults to same as project
 :version "0.1.0-SNAPSHOT"
 ;; a msg to be output when someone uses your template
 :msg "Making a new template based on my-template!"
 ;; the relative path to a file that will serve as the template's
 ;; (not the readme of a project generated from the template)
 :readme "resources/README.template.md"
 ;; same as readme
 :license "resources/LICENSE"
 ;; a map of relative files and paths with which to override them.
 ;; A path of nil will exclude the file.
 :file-overrides {"README.md" "resources/README.new.md"
                  "something_to_exclude.clj" nil}
 }
```

to test if you're template actually works

```shell
cd lein-template
lein install
```

add the template into the ```:plugins``` vector of the ```:user``` profile in your ```~/.lein/profiles.clj```.

```shell
lein new my-template test-project
```

and try it out.

## License

Copyright © 2014 Dylan Butman

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
