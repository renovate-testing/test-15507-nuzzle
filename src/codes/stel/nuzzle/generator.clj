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

(defn create-tag-index
  "Create a map of pages that are the tag index pages"
  [site-config]
  (->> site-config
       ;; Create a map shaped like tag -> [page-ids]
       (reduce-kv
        (fn [m id {:keys [tags]}]
          ;; merge-with is awesome!
          (if tags (merge-with into m (zipmap tags (repeat [id]))) m))
        {})
       ;; Then change the val into a map with more info
       (reduce-kv
        (fn [m tag ids]
          (assoc m [:tags tag] {:index ids
                                :title (str "#" (name tag))
                                :uri (str "/tags/" (name tag) "/")}))
        {})))

(defn create-group-index
  "Create a map of all pages that serve as a location-based index for other
  pages. For example, if there is an entry in site-config with key
  [:blog-posts :foo], then this function will create a map with a [:blog-posts]
  entry and the value will be a map with :index [[:blog-posts :foo]]."
  [config]
  (->> config
       ;; Create a map shaped like group -> [page-ids]
       (reduce-kv
        (fn [m id _]
          (if (and (vector? id) (> (count id) 1))
            (merge-with into m {(vec (butlast id)) [id]}) m))
        {})
       ;; Then change the val into a map with more info
       (reduce-kv
        (fn [m group-id ids]
          (assoc m group-id {:index ids
                             :title (util/kebab-case->title-case (last group-id))
                             :uri (util/id->uri group-id)}))
        {})))

(defn create-render-content-fn
  "Create a function that turned the :content file into html, wrapped with the
  hiccup raw identifier."
  [id content]
  {:pre [(vector? id) (or (nil? content) (string? content))]}
  (if-not content
    ;; If :content is not defined, just make a function that returns nil
    (constantly nil)
    (if-let [content-file (io/resource content)]
      (let [ext (fs/extension content-file)]
        (cond
         ;; If a html or svg file, just slurp it up
         (or (= "html" ext) (= "svg" ext))
         (fn render-html []
           (hiccup/raw (slurp content-file)))
         ;; If markdown, convert to html
         (or (= "markdown" ext) (= "md" ext))
         (fn render-markdown []
           (hiccup/raw (md-to-html-string (slurp content-file))))
         ;; If extension not recognized, throw Exception
         :else (throw (ex-info (str "Filetype of content file " content " for id " id " not recognized")
                      {:id id :content content}))))
      ;; If content-file is defined but it can't be found, throw an Exception
      (throw (ex-info (str "Resource " content " for id " id " not found")
                      {:id id :content content})))))

(defn realize-pages
  "Adds :uri, :render-content-fn keys to each page in the site-config."
  [site-config]
  {:pre [map? site-config]}
  (reduce-kv
   (fn [m id {:keys [content uri] :as v}]
     (if (vector? id)
       (assoc m id
              (merge v {:uri (or uri (util/id->uri id))
                        :render-content-fn
                        (create-render-content-fn id content)}))
       (assoc m id v)))
   {} site-config))

(defn gen-id->info
  "Generate the helper function id->info from the realized-site-config. This
  function takes a page id (vector of 0 or more keywords) and returns the page
  information with added key :id->info with value id->info function attached."
  [realized-site-config]
  {:pre [(map? realized-site-config)] :post [(fn? %)]}
  (fn id->info [id]
    (if-let [entity (get realized-site-config id)]
      (assoc entity :id->info id->info)
      (throw (ex-info (str "id->info error: id " id " not found")
                      {:id id})))))

(defn remove-drafts
  "Remove page entries from site-config map if they are marked as a draft with
  :draft? true kv pair."
  [site-config]
  (reduce-kv
   (fn [m id {:keys [draft?] :as v}]
     (if (and (vector? id) draft?)
       m
       (assoc m id v)))
   {}
   site-config))

(defn realize-site-config
  "Creates fully realized site-config datastructure with or without drafts."
  [site-config remove-drafts?]
  {:pre [(map? site-config) (boolean? remove-drafts?)]}
  ;; Allow users to define their own overrides via deep-merge
  (let [site-config (if remove-drafts?
                      (do (log/info "Removing drafts") (remove-drafts site-config))
                      (do (log/info "Including drafts") site-config))]
    (->> site-config
         ;; Make sure there is a root index.html file
         (util/deep-merge {[] {:uri "/"}})
         (util/deep-merge (create-group-index site-config))
         (util/deep-merge (create-tag-index site-config))
         (realize-pages))))

(defn generate-page-list
  [realized-site-config]
  (->> realized-site-config
       ;; If key is vector, then it is a page
       (reduce-kv (fn [page-list id v]
                    (if (vector? id)
                      ;; Add the page id to the map
                      (conj page-list (assoc v :id id))
                      page-list)) [])
       ;; Add id->info helper function to each page
       (map #(assoc % :id->info (gen-id->info realized-site-config)))))

(defn generate-site-index
  [page-list render-fn]
  (->> page-list
       (map (fn [page] (when-let [render-result (render-fn page)]
                         [(:uri page)
                          (fn [_]
                            (str "<!DOCTYPE html>"
                                 (hiccup/html render-result)))])))
       (into {})))

(defn export-site-index
  [site-index static-dir target-dir]
  (when (and static-dir (not (io/resource static-dir)))
    (throw (ex-info (str static-dir " is not a valid resource directory")
                    {:static-dir static-dir})))
  (let [assets (when static-dir (io/resource static-dir))]
    (fs/create-dirs target-dir)
    (stasis/empty-directory! target-dir)
    (stasis/export-pages site-index target-dir)
    (if assets
      (do (log/info (str "Copying contents of static directory: " static-dir))
        (fs/copy-tree assets target-dir))
      (log/info "No static directory provided"))
    (log/info (str "Build successful! Located in: " target-dir))))

(defn global-config->site-index
  "This function is a shortcut to get from a global-config to a site-index."
  [{:keys [site-config remove-drafts? render-fn]}]
  {:pre [(or (map? site-config) (string? site-config))
         (boolean? remove-drafts?)
         (fn? render-fn)]}
  (-> site-config
      (load-site-config)
      (realize-site-config remove-drafts?)
      (generate-page-list)
      (generate-site-index render-fn)))
