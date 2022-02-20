(ns codes.stel.nuzzle.generator-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [codes.stel.nuzzle.util :as util]
            [codes.stel.nuzzle.generator :as gen]))

(def global-config {:site-config {[:blog :foo] {:title "Foo"} [:about] {:title "About"}}
                    :remove-drafts? false
                    :render-fn (constantly [:h1 "Test"])
                    :static-dir "public"
                    :target-dir "/tmp/dist"})

(deftest create-tag-index
  (is (= {[:tags :bar] {:index [[:blog :foo]], :title "#bar", :uri "/tags/bar/"},
          [:tags :baz] {:index [[:blog :foo]], :title "#baz", :uri "/tags/baz/"}}
         (gen/create-tag-index {[:blog :foo] {:tags [:bar :baz]} [:about] {} }) )))

(deftest id->uri
  (is (= "/blog-posts/my-hobbies/" (util/id->uri [:blog-posts :my-hobbies])))
  (is (= "/about/" (util/id->uri [:about]))))

(deftest create-group-index
  (is (= (gen/create-group-index {[:blog :foo] {:title "Foo"} [:blog :archive :baz] {:title "Baz"} [:projects :bee] {:title "Bee"}})
         {[:blog]
          {:index [[:blog :foo]], :title "Blog", :uri "/blog/"},
          [:blog :archive]
          {:index [[:blog :archive :baz]],
           :title "Archive",
           :uri "/blog/archive/"},
          [:projects]
          {:index [[:projects :bee]], :title "Projects", :uri "/projects/"}})))

(deftest load-site-config
  (is (= {:site-config {[:blog :foo] {:title "Foo"}, [:about] {:title "About"}},
          :remove-drafts? false,
          :static-dir "public",
          :target-dir "/tmp/dist"}
         (-> (gen/load-site-config global-config)
             (dissoc :render-fn)))))

(deftest realize-site-config
  (is (= (gen/realize-site-config (:site-config global-config) (:remove-drafts? global-config)))))

#_
(deftest export
  (let [y {[:about] {:title "About"}}
        x {:config y :include-drafts? true :render-fn (constantly "<h1>Test</h1>") :target-dir "/tmp/dist"}]
    (generator/export x)))

(comment (run-tests))