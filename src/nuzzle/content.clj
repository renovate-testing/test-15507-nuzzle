(ns nuzzle.content
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.walk :as w]
   [cybermonday.core :as cm]
   [cybermonday.utils :as cu]
   [nuzzle.hiccup :as hiccup]
   [nuzzle.log :as log]
   [nuzzle.util :as util]
   [vimhelp.core :as vimhelp]))

(defn quickfigure-shortcode
  [[_tag {:keys [src title] :as _attr}]]
  [:figure [:img {:src src}]
   [:figcaption [:h4 title]]])

(defn gist-shortcode
  [[_tag {:keys [user id] :as _attr}]]
  [:script {:type "application/javascript"
            :src (str "https://gist.github.com/" user "/" id ".js")}])

(defn youtube-shortcode
  [[_tag {:keys [title id] :as _attr}]]
  [:div {:style "position: relative; padding-bottom: 56.25%; height: 0; overflow: hidden;"}
   [:iframe {:src (str "https://www.youtube.com/embed/" id)
             :style "position: absolute; top: 0; left: 0; width: 100%; height: 100%; border:0;"
             :title title :allowfullscreen true}]])

(defn vimhelp-shortcode
  [[_tag {:keys [src badrefs] :or {badrefs ""} :as _attr}]]
  (vimhelp/help-file->hiccup src (set (str/split badrefs #","))))

(defn render-shortcode
  [[tag & _ :as hiccup]]
  (case tag
    :quickfigure (quickfigure-shortcode hiccup)
    :gist (gist-shortcode hiccup)
    :vimhelp (vimhelp-shortcode hiccup)
    :youtube (youtube-shortcode hiccup)
    hiccup))

(defn walk-hiccup-for-shortcodes
  [hiccup]
  {:pre [(sequential? hiccup)]}
  (if (list? hiccup)
    (map walk-hiccup-for-shortcodes hiccup)
    (w/prewalk
     (fn [item]
       (if (cu/hiccup? item)
         (if (-> item second map?)
           (render-shortcode item)
           (render-shortcode (apply vector (first item) {} (rest item))))
         item))
     hiccup)))

(defn generate-chroma-command
  [file-path language config]
  (let [{:keys [style line-numbers?]} (get-in config [:markdown-opts :syntax-highlighting])]
    ["chroma" (str "--lexer=" language) "--formatter=html" "--html-only"
     "--html-prevent-surrounding-pre" (when style "--html-inline-styles")
     (when style (str "--style=" style)) (when line-numbers? "--html-lines")  file-path]))

(defn generate-pygments-command
  [file-path language config]
  (let [{:keys [style line-numbers?]} (get-in config [:markdown-opts :syntax-highlighting])
        ;; TODO: turn nowrap on for everything if they release my PR
        ;; https://github.com/pygments/pygments/issues/2127
        options [(when-not line-numbers? "nowrap") (when style "noclasses")
                 (when style (str "style=" style))
                 (when line-numbers? "linenos=inline")]]
    ["pygmentize" "-f" "html" "-l" language "-O"
     (->> options (remove nil?) (str/join ",")) file-path]))

(defn generate-highlight-command
  [file-path language config]
  (->>
   (case (get-in config [:markdown-opts :syntax-highlighting :provider])
    :chroma (generate-chroma-command file-path language config)
    :pygments (generate-pygments-command file-path language config))
   (remove nil?)))

(defn highlight-code [code language config]
  (let [tmp-file (fs/create-temp-file)
        tmp-file-path (-> tmp-file fs/canonicalize str)
        _ (spit tmp-file-path code)
        highlight-command (generate-highlight-command tmp-file-path language config)
        {:keys [exit out err]} (apply util/safe-sh highlight-command)]
    (if (not= 0 exit)
      (do
        (log/warn "Syntax highlighting command failed:" (str/join " " highlight-command))
        (log/warn err)
        code)
      (do
        (fs/delete-if-exists tmp-file)
        out))))

(defn code-block->hiccup [[_tag-name {:keys [language]} code] config]
  (if (and language (get-in config [:markdown-opts :syntax-highlighting]))
    [:code.code-block
     [:pre (hiccup/raw (highlight-code code language config))]]
    [:code.code-block [:pre code]]))

(defn process-markdown-file [file config]
  (let [code-block-with-config #(code-block->hiccup % config)
        lower-fns {:markdown/fenced-code-block code-block-with-config
                   :markdown/indented-code-block code-block-with-config}
        ;; Avoid the top level :div {}
        [_ _ & hiccup] (-> file
                           slurp
                           (cm/parse-body {:lower-fns lower-fns}))]
    (walk-hiccup-for-shortcodes hiccup)))

(defn process-html-file
  [content-file _config]
  (hiccup/raw (slurp content-file)))

(defn create-render-content-fn
  "Create a function that turns the :content file into the correct form for the
  hiccup compiler: vector, list, or raw string"
  [id content config]
  {:pre [(or (vector? id) (keyword? id)) (or (nil? content) (string? content))]}
  (if-not content
    ;; If :content is not defined, just make a function that returns nil
    (constantly nil)
    (let [content-file (fs/file content)
          content-ext (fs/extension content-file)]
      (if (fs/exists? content-file)
        (cond
         (contains? #{"md" "markdown"} content-ext)
         (fn render-content [] (process-markdown-file content-file config))
         (contains? #{"html" "htm"} content-ext)
         (fn render-content [] (process-html-file content-file config))
         :else (throw (ex-info (str "Content file " (fs/canonicalize content-file)
                                    " for id " id " has unrecognized extension "
                                    content-ext ". Must be one of: md, markdown, html, htm")
                               {:id id :content content})))
        ;; If markdown-file is defined but it can't be found, throw an Exception
        (throw (ex-info (str "Content file " (fs/canonicalize content-file)
                             " for id " id " not found")
                        {:id id :content content}))))))
