(ns picard.test.utils.buffer
  (:use
   picard.utils.buffer
   picard.utils.conversions
   clojure.test))

(deftest buffer-transfer
  (let [src (to-byte-buffer "Hello")
        dst (allocate 5)]
    (is (transfer src dst))
    (is (= src dst)))

  (let [src (to-byte-buffer "Hello")
        dst (allocate 10)]
    (is (transfer src dst))
    (is (= (rewind src)
           (-> dst rewind (focus 5)))))

  (let [src (to-byte-buffer "Hello")
        dst (allocate 3)]
    (is (not (transfer src dst)))
    (is (= (rewind dst) (to-byte-buffer "Hel")))))

(deftest buffer-batch
  (let [bufs (atom [])
        callback
        (fn [buf]
          (swap! bufs #(conj % buf)))]

    (batch
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(to-byte-buffer "HelloWorld")]))
    (reset! bufs [])

    (batch
     10
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(to-byte-buffer "HelloWorld")]))
    (reset! bufs [])

    (batch
     8
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(to-byte-buffer "HelloWor")
                  (to-byte-buffer "ld")]))
    (reset! bufs [])

    (batch
     3
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(to-byte-buffer "Hel")
                  (to-byte-buffer "loW")
                  (to-byte-buffer "orl")
                  (to-byte-buffer "d")]))
    (reset! bufs [])

    (batch
     (fn [b]
       (b "Hello" " " "World")
       (b " " "Goodbye"))
     callback)

    (is (= @bufs [(to-byte-buffer "Hello World Goodbye")]))))
