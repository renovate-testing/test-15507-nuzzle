(ns codes.stel.nuzzle.api
  (:require [babashka.fs :as fs]
            [codes.stel.nuzzle.generator :as gen]
            [ring.middleware.resource :refer [wrap-resource]]
            [stasis.core :as stasis]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [org.httpkit.server :refer [run-server]]
            [taoensso.timbre :as log]))

(defn inspect
  "Allows the user to inspect the site-config after modifications such as the
  drafts being optionally removed, the group and tag index pages being added,
  and :uri and :render-resource fields being added."
  [{:keys [site-config remove-drafts?]}]
  (-> site-config
      (gen/load-site-config)
      (gen/realize-site-config remove-drafts?)))

(defn export
  "Exports the website to :target-dir. The :static-dir is overlayed on top of
  the :target-dir after the web pages have been exported."
  [{:keys [site-config remove-drafts? static-dir target-dir render-page rss-opts]
    :or {target-dir "dist"} :as global-config}]
  {:pre [(or (map? global-config) (string? global-config))
         (string? static-dir)
         (string? target-dir)
         (or (nil? rss-opts) (map? rss-opts))]}
  (log/info "🔨🐈 Exporting static site to disk")
  (let [realized-site-config
        (-> site-config
            (gen/load-site-config)
            (gen/realize-site-config remove-drafts?))
        rss-filename (or (:filename rss-opts) "rss.xml")
        rss-file (fs/file target-dir rss-filename)
        rss-feed (gen/create-rss-feed realized-site-config rss-opts)]
    (-> realized-site-config
        (gen/generate-page-list)
        (gen/generate-site-index render-page)
        (gen/export-site-index static-dir target-dir))
    (when rss-feed
      (do (log/info "📰🐈 Creating RSS file: " rss-filename)
        (spit rss-file rss-feed)))))

(defn start-server
  "Starts a server using http-kit for development."
  [{:keys [static-dir dev-port] :or {dev-port 5868} :as global-config}]
  (log/info (str "✨🐈 Starting development server on port " dev-port))
  (letfn [(maybe-wrap-resource [app static-dir]
            (if static-dir
              (do (log/info (str "Wrapping static resources directory: " static-dir))
                (wrap-resource app static-dir))
              (do (log/info "No static resource directory provided") app)))]
    (-> (stasis/serve-pages #(gen/global-config->site-index global-config))
        (maybe-wrap-resource static-dir)
        (wrap-content-type)
        (wrap-stacktrace)
        (run-server {:port dev-port}))))
