(ns nuzzle.api
  (:require [babashka.fs :as fs]
            [nuzzle.config :as conf]
            [nuzzle.generator :as gen]
            [nuzzle.log :as log]
            [nuzzle.ring :as ring]
            [nuzzle.rss :as rss]
            [nuzzle.util :as util]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [org.httpkit.server :as http]))

(defn realize
  "Allows the user to visualize the site data after Nuzzle's modifications."
  [& {:as config-overrides}]
  {:pre [(or (nil? config-overrides) (map? config-overrides))]}
  (let [config (conf/load-config config-overrides)]
    (log/info "🔍🐈 Printing realized site data for inspection")
    (-> config
        (gen/realize-site-data)
        (util/convert-site-data-to-vector))))

(defn export
  "Exports the website to :export-dir. The :overlay-dir is overlayed on top of
  the :export-dir after the web pages have been exported."
  [& {:as config-overrides}]
  {:pre [(or (nil? config-overrides) (map? config-overrides))]}
  (let [{:keys [rss-channel export-dir] :as config}
        (conf/load-config config-overrides)
        realized-site-data (gen/realize-site-data config)
        rss-file (fs/file export-dir "rss.xml")
        rss-feed (rss/create-rss-feed realized-site-data rss-channel)]
    (log/info "🔨🐈 Exporting static site to:" export-dir)
    (gen/export-site config)
    (when rss-feed
      (log/info "📰🐈 Creating RSS file:" (fs/canonicalize rss-file))
      (spit rss-file rss-feed))
    (log/info "✅🐈 Export successful")))

(defn serve
  "Starts a server using http-kit for development."
  [& {:as config-overrides}]
  {:pre [(or (nil? config-overrides) (map? config-overrides))]}
  (let [{:keys [dev-port] :as config}
        (conf/load-config config-overrides)]
    (log/info (str "✨🐈 Starting development server on port " dev-port))
    (-> (ring/wrap-serve-pages)
        (ring/wrap-overlay-dir)
        (ring/wrap-load-config config-overrides)
        (wrap-content-type)
        (wrap-stacktrace)
        (http/run-server {:port (:dev-port config)}))))
