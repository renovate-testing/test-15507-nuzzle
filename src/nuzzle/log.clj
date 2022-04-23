(ns nuzzle.log)

(defn log-time []
  (let [now (java.time.LocalDateTime/now)
        formatter (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")]
    (.format now formatter)))

(defn log-gen [level]
  (fn [& items]
    (->> items
         (map str)
         (apply println (log-time) level))))

(def info (log-gen "INFO"))

(def warn (log-gen "WARN"))

(def error (log-gen "ERROR"))

(comment (info "test" "ok"))

