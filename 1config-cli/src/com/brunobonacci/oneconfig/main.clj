(ns com.brunobonacci.oneconfig.main
  (:require [com.brunobonacci.oneconfig.cli :as cli]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str])
  (:gen-class))


(def ^:dynamic *repl-session* false)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                            ;;
;;                           ---==| M A I N |==----                           ;;
;;                                                                            ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn help! [errors]
  (println "
      1config cli
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  A command line tool for managing configurations in different environments.

Usage:

   cfg1 <OPERATION> -e <ENVIRONMENT> -k <SERVICE> -v <VERSION> [-b <BACKEND>] [-t <TYPE>] <VALUE>

   WHERE:
   ---------

   OPERATION:
      - GET       : retrieve the current configuration value for
                  : the given env/service/version combination
      - SET       : sets the value of the given env/service/version combination
      - LIST      : lists the available keys for the given backend
      - INIT      : initialises the given backend (like create the table if necessary)

   OPTIONS:
   ---------
   -h   --help                 : this help
   -b   --backend   BACKEND    : only \"dynamo\" is currently supported
   -e   --env   ENVIRONMENT    : the name of the environment like \"prod\", \"dev\", \"st1\" etc
   -k   --key       SERVICE    : the name of the system or key for which the configuration if for,
                               : exmaple: \"service1\", \"db.pass\" etc
   -v   --version   VERSION    : a version number for the given key in the following format: \"2.12.4\"
   -c   --change-num CHANGENUM : used with GET returns a specific configuration change.
        --with-meta            : whether to include meta data for GET operation
        --output-format FORMAT : either \"table\" or \"cli\" default is \"table\" (only for list)
   -C                          : same as `--output-format=cli`
   -t   --content-type TYPE    : one of \"edn\", \"text\" or \"json\", default is \"edn\"

Example:

   (*) To initialise a given backend
   cfg1 INIT -b dynamo

   (*) To set the configuration value of a service called 'service1' use:
   cfg1 SET -b dynamo -e test -k 'service1' -v '1.6.0' -t edn '{:port 8080}'

   (*) To read last configuration value for a service called 'service1' use:
   cfg1 GET -b dynamo -e test -k 'service1' -v '1.6.0'

   (*) To read a specific changeset for a service called 'service1' use:
   cfg1 GET -b dynamo -e test -k 'service1' -v '1.6.0' -c '3563412132'

   (*) To list configuration with optional filters
   cfg1 LIST -b dynamo -e prod -k ser -v 1.


NOTE: use AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY or AWS_PROFILE to
      set the access to the target AWS account.

" (str/join "\n" errors))
  (System/exit 1))


(defn normal-exit!
  []
  (when-not *repl-session*
    (shutdown-agents)
    (System/exit 0)))


(def cli-options
  [["-h"  "--help"]

   ["-b"  "--backend BACKEND"
    :default "dynamo"
    :validate [(partial re-find #"(?i)^(dynamo)$") "Must be one of: dynamo"]]


   [nil "--with-meta"]

   [nil "--output-format OUTPUT"
    :parse-fn keyword
    :validate [#{:table :cli}]
    :default :table]

   ["-C" "--C"]

   ["-e"  "--env ENV"]

   ["-k"  "--key KEY"]

   ["-v"  "--version VER"
    :default ""]

   ["-c"  "--change-num CHANGENUM"
    :parse-fn (fn [num-str] (when num-str (Long/parseLong num-str)))]

   ["-t"  "--content-type TYPE"
    :default "edn"
    :validate [(partial re-find #"^(edn|text|json)$") "Must be one of: edn, text, json"]]])


(def content-map
  {"edn"  "application/edn"
   "json" "application/json"
   "text" "text/plain"})


(defn -main [& args]
  (let [{:keys [options arguments errors] :as cli} (parse-opts args cli-options)
        {:keys [help backend env key version change-num
                content-type with-meta output-format]} options
        [op value] arguments
        op (when op (keyword (str/lower-case op)))
        content-type (content-map content-type)
        backend-name (keyword backend)
        output-format (if (:C options) :cli output-format)]

    (cond
      help            (help! [])
      errors          (help! errors)
      (not op)        (help! ["MISSING: required argument: operation"])
      (not
       (#{:get :set :init :list} op)) (help! ["INVALID operation: must be either GET, SET, LIST or INIT"])
      (and (= op :set) (not value)) (help! ["MISSING: required argument: value"])

      :else
      (do
        (case op
          ;;
          ;; INIT
          ;;
          :init (cli/init-backend! backend-name)
          ;;
          ;; SET
          ;;
          :set (cli/set! (cli/backend backend-name)
                          {:env env :key key :content-type content-type
                           :version version :value
                           (or (cli/decode content-type value) (System/exit 2))})
          ;;
          ;; GET
          ;;
          :get (cli/get! (cli/backend backend-name)
                          {:env env :key key :version version :change-num change-num}
                           :with-meta with-meta)


          ;;
          ;; LIST
          ;;
          :list (cli/list! (cli/backend backend-name)
                            {:env env :key key :version version}
                            :output-format output-format :backend-name backend-name))
        (normal-exit!)))))
