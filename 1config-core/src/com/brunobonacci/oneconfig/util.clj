(ns com.brunobonacci.oneconfig.util
  (:require [safely.core :refer [safely]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [schema.core :as s]
            [clojure.string :as str])
  (:import [java.util.zip GZIPInputStream GZIPOutputStream]
           [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]))





(defn sem-ver
  "Returns a vector of the numerical components of a 3-leg version number.
  eg:

       (sem-ver \"1.2.3\") ;;=> [1 2 3]
       (sem-ver \"1.2-alpha\") ;;=> nil

  "
  [ver]
  (when ver
    (when-let [components (re-find #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})" ver)]
      (mapv (fn [^String n] (Long/parseLong n)) (rest components)))))


(def config-entry-schema
  {:env s/Str
   :key s/Str
   :version (s/pred sem-ver "Version must be of the following form \"1.12.3\"")
   :value s/Any
   (s/optional-key :content-type) s/Str})


(def config-entry-request-schema
  {:env s/Str
   :key s/Str
   :version (s/pred sem-ver "Version must be of the following form \"1.12.3\"")
   (s/optional-key :change-num) s/Int})


(defn check-entry [entry]
  (s/check config-entry-schema entry))


(defn valid-entry? [entry]
  (s/validate config-entry-schema entry))


(defn valid-entry-request? [entry]
  (s/validate config-entry-request-schema entry))


(defn comparable-version
  "takes a 3-leg version which must and return a version string
   which it can be compare lexicographically and maintain it's semantic
   version meaning."
  [ver]
  (->> ver
       sem-ver
       (map (partial format "%03d"))
       (reduce str)))



(defn env
  "Returns the value of a environment variable or a map with the all variables."
  ([]
   (into {} (System/getenv)))
  ([var]
   (System/getenv var)))



(defn system-property
  "Returns the value of a system property or a map with all properties."
  ([]
   (into {} (System/getProperties)))
  ([property]
   (System/getProperty property)))



(defn read-file
  "reads a file and returns its content as a string or nil if not found or can't be read"
  [file]
  (safely
   (slurp file :encoding "utf-8")
   :on-error
   :default nil))



(defn homedir
  "returns the current home dir or nil if not found"
  []
  (env "HOME"))



(defn list-files
  "Returns a lazy list of files and directories for a given path and reg-ex"
  ([pattern dir & {:keys [as-string]
                   :or {as-string false}}]
   (let [matches? (if pattern
                    (fn [^java.io.File f] (re-find pattern (.getCanonicalPath f)))
                    (constantly true))
         mapper   (if as-string (fn [^java.io.File f] (.getCanonicalPath f)) identity)]
     (->> (list-files dir)
          (filter matches?)
          (map mapper))))
  ([dir]
   (if-let [path (and dir (.exists (io/file dir)) (io/file dir))]
     (if (.isDirectory path)
       (lazy-seq
        (cons path
              (mapcat list-files
                      (.listFiles path))))
       [path])
     [])))



(defn search-candidates
  "given a list of candidates searches for configuration files."
  [& candidates]
  (filter identity (flatten candidates)))



(defn read-edn-file
  "reads a EDN file and returns its content or nil if invalid"
  [file]
  (safely
   (some-> file slurp edn/read-string)
   :on-error :default nil))


(defn entry-record
  "Given an internal entry, it returns only the keys which are public"
  [entry]
  (when entry
    (select-keys entry [:env :key :version :content-type :value :change-num])))



(defn configuration-file-search
  "It searches configuration files in a number of different locations.
   it returns a list of entries or nil if no configuration files are found."
  []
  (some (fn [f] (and (.exists (io/file f)) f))
        (search-candidates
         (env "ONECONFIG_FILE")
         (system-property "1config.file")
         (io/resource "1config.edn")
         "./1config/1config.edn"
         (str (homedir) "/.1config/1config.edn"))))



(defmulti decode (fn [form value] form))

(defmethod decode "application/edn"
  [_ value]
  (edn/read-string value))

(defmethod decode "application/json"
  [_ value]
  (json/parse-string value true))

(defmethod decode "text/plain"
  [_ value]
  value)


(defmulti encode (fn [form value] form))


(defmethod encode "application/edn"
  [_ value]
  (pr-str value))


(defmethod encode "application/json"
  [_ value]
  (json/generate-string value))


(defmethod encode "text/plain"
  [_ value]
  (cond
    (string? value) value
    (number? value) (str value)
    :else
    (throw (ex-info "Illegal value, a string expected" {:value value}))))


(defn- filter-entries [{:keys [key env version]} entries]
  (->> entries
       (filter #(str/starts-with? (:env %) (or env "")))
       (filter #(str/starts-with? (:key %) (or key "")))
       (filter #(str/starts-with? (:version %) (or version "")))))


(defn list-entries [filters entries]
  (->> entries
       (filter-entries filters)
       (sort-by (juxt :key :env :version :change-num))
       (map #(dissoc % :__ver_key :__sys_key :value :zver))))


(comment
  (defmethod decode "gzip"
    [_ value]
    (let [bout (ByteArrayOutputStream.)
          in  (GZIPInputStream. value)]
      (io/copy in bout)
      (if (instance? InputStream value)
        (.close value))
      (ByteArrayInputStream. (.toByteArray bout)))))


(defn unmarshall-value
  [{:keys [content-type] :as config-entry}]
  (update config-entry :value (partial decode content-type)))


(defn marshall-value
  [{:keys [content-type] :as config-entry}]
  (update config-entry :value (partial encode content-type)))


;; mapcat is not lazy so defining one
(defn lazy-mapcat
  "maps a function over a collection and
   lazily concatenate all the results."
  [f coll]
  (lazy-seq
   (if (not-empty coll)
     (concat
      (f (first coll))
      (lazy-mapcat f (rest coll))))))
