(ns picard.test.utils.buffer
  (:use
   picard.utils.buffer
   clojure.test)
  (:import
   [java.nio
    BufferOverflowException]))

(deftest making-buffer
  (is (= 1024 (capacity (buffer))))

  (is (= 10 (capacity (buffer 10))))

  (is (= (to-buffer "Hello")
         (buffer "Hello")))

  (is (= (to-buffer "Hello")
         (buffer "Hel" "lo")))

  (is (= (to-buffer "Hello world")
         (buffer "Hello" " " "world")))

  (is (= 100 (capacity (buffer 100 "Hello"))))
  (is (= 100 (capacity (buffer 100 "Hello" "World"))))

  (is (thrown?
       BufferOverflowException
       (buffer 5 "Hello world"))))

(deftest buffer-transfer
  (let [src (buffer "Hello")
        dst (buffer 5)]
    (is (transfer src dst))
    (is (= src dst)))

  (let [src (buffer "Hello")
        dst (buffer 10)]
    (is (transfer src dst))
    (is (= (rewind src)
           (-> dst rewind (focus 5)))))

  (let [src (buffer "Hello")
        dst (buffer 3)]
    (is (not (transfer src dst)))
    (is (= (rewind dst) (buffer "Hel")))))

(deftest buffer-wrap
  (is (= (to-channel-buffer "Hello world")
         (wrap "Hello " "world"))))

(deftest wraapping-iterables
  (is (= (wrap "Hello" "world")
         (buffer ["Hello" "world"]))))

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

    (is (= @bufs [(buffer "HelloWorld")]))
    (reset! bufs [])

    (batch
     10
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(buffer "HelloWorld")]))
    (reset! bufs [])

    (batch
     8
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(buffer "HelloWor")
                  (buffer "ld")]))
    (reset! bufs [])

    (batch
     3
     (fn [b]
       (b "Hello")
       (b "World"))
     callback)

    (is (= @bufs [(buffer "Hel")
                  (buffer "loW")
                  (buffer "orl")
                  (buffer "d")]))
    (reset! bufs [])

    (batch
     (fn [b]
       (b "Hello" " " "World")
       (b " " "Goodbye"))
     callback)

    (is (= @bufs [(buffer "Hello World Goodbye")]))))
