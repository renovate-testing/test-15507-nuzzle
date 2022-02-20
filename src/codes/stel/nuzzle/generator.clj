(ns codes.stel.nuzzle.generator
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [codes.stel.nuzzle.hiccup :as hiccup]
            [codes.stel.nuzzle.util :as util]
            [markdown.core :refer [md-to-html-string]]
            [stasis.core :as stasis]
            [taoensso.timbre :as log]))

(defn load-site-config
  "Turn the site-config into a map. It can be defined as a map or a string. If
  it is a string, it should be a path to an edn resource. Attempt to load that
  resource and make sure it as a map."
  [site-config]
  {:pre [(or (map? site-config) (string? site-config))] :post [(map? %)]}
  (if (map? site-config)
    site-config
    (try
      (-> site-config
          (io/resource)
          (slurp)
          (edn/read-string))
      (catch Throwable _
        (throw (ex-info
                (str "Site config file: " site-config " could not be read. Make sure the file is in your classpath and the contents are a valid EDN map.")
                {:config site-config}))))))
