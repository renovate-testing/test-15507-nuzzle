(ns nuzzle.schemas-test
  (:require
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [malli.transform :as mt]
   [nuzzle.schemas :as schemas]))

(deftest markdown
  (is (m/validate schemas/markdown-opts
                  {:syntax-highlighting
                   {:provider :chroma
                    :style "emacs"}
                   :shortcodes
                   {:foobar 'shortcodes/foobar
                    :foobaz 'shortcodes/foobaz}})))

(deftest local-date
  (is (m/validate schemas/local-date
                  (m/decode schemas/local-date "2022-05-04"
                            (mt/transformer {:name :local-date}))))
  (is (not (m/validate schemas/local-date
                       (m/decode schemas/local-date "2022-05-04jhfklsdjf"
                                 (mt/transformer {:name :local-date}))))))
