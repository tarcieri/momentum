(ns picard.middleware.cookie
  (:use
   [picard.helpers])
  (:require
   [clojure.contrib.logging :as log])
  (:import
   [org.jboss.netty.handler.codec.http
    Cookie
    CookieDecoder
    CookieEncoder]))

(defn cookie
  [app]
  (defmiddleware
    [state next-dn next-up]
    app

    :upstream
    (defstream
      (request [[hdrs body :as req]]
        (if (hdrs "cookie")
          ;; if there is a cookie header then parse the cookies and
          ;; send them up
          (try
            (let [decoder (CookieDecoder.)
                  cookies (into #{} (seq (.decode decoder (hdrs "cookie"))))
                  hdrs (assoc hdrs :cookies cookies)
                  hdrs (dissoc hdrs "cookie")]
              (next-up :request [hdrs body]))
            (catch Exception e
              (log/warn "bad cookie header" e)
              (next-up :request req)))
          ;; otherwise pass through unmodified
          (next-up :request req)))

      (response [[status hdrs body :as resp]]
        ;; if there's a header for cookies then encode them
        (if (:cookies hdrs)
          (try
            (let [encoder (CookieEncoder. true)
                  hdrs (dissoc hdrs :cookies)]
              (doseq [cookie (:cookies hdrs)] (.addCookie encoder cookie))
              (next-up :response [status (assoc hdrs "Set-Cookie" (.encode encoder)) body]))
            (catch Exception e
              (log/warn "bad cookie" e)
              next-up :response resp))
          ;; otherwise pass thr response through unaltered
          (next-up :response resp)))

      (else [evt val]
        (next-up evt val)))

    :downstream
    (defstream
      (request [[hdrs body :as req]]
        (if (hdrs "cookie")
          ;; if there is a cookie header then parse the cookies and
          ;; send them up
          (try
            (let [decoder (CookieDecoder.)
                  cookies (into #{} (seq (.decode decoder (hdrs "cookie"))))
                  hdrs (assoc hdrs :cookies cookies)
                  hdrs (dissoc hdrs "cookie")]
              (next-dn :request [hdrs body]))
            (catch Exception e
              (log/warn "bad cookie header" e)
              (next-dn :request req)))
          ;; otherwise pass through unmodified
          (next-dn :request req)))

      (response [[status hdrs body :as resp]]
        ;; if there's a header for cookies then encode them
        (if (:cookies hdrs)
          (try
            (let [encoder (CookieEncoder. true)
                  hdrs (dissoc hdrs :cookies)]
              (doseq [cookie (:cookies hdrs)] (.addCookie encoder cookie))
              (next-dn :response [status (assoc hdrs "Set-Cookie" (.encode encoder)) body]))
            (catch Exception e
              (log/warn "bad cookie" e)
              next-dn :response resp))
          ;; otherwise pass thr response through unaltered
          (next-dn :response resp)))

      (else [evt val]
        (next-dn evt val)))))
