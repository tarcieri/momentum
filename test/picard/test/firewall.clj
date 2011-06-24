(ns picard.test.firewall
  (:use
   clojure.test
   picard.firewall))

(deftest ^{:network true} denies-all-ips-by-default
  (let [rules (define-rules "")]
    (doseq [ip ["127.0.0.1"
                "74.125.224.114"
                "localhost"
                "www.google.com"
                "10.0.1.13"]]
      (is (not (allows? rules ip)))
      (is (denies? rules ip)))))

(deftest ^{:network true} handles-allow-all-rule
  (let [rules (define-rules "+i:*")]
    (doseq [ip ["127.0.0.1"
                "74.125.224.114"
                "localhost"
                "www.google.com"
                "10.0.1.13"]]
      (is (allows? rules ip))
      (is (not (denies? rules ip))))))

(deftest ^{:network true} handles-deny-all-rule
  (let [rules (define-rules "-i:*")]
    (doseq [ip ["127.0.0.1"
                "74.125.224.114"
                "localhost"
                "www.google.com"
                "10.0.1.13"]]
      (is (not (allows? rules ip)))
      (is (denies? rules ip)))))

(deftest ^{:network true} handles-blocking-localhost
  (let [rules (define-rules "+i:*, -i:127.*")]
    (doseq [ip ["127.0.0.1"
                "127.0.0.1"
                "127.1.0.1"
                "localhost"]]
      (is (not (allows? rules ip)))
      (is (denies? rules ip)))

    (doseq [ip ["74.125.224.114"
                "www.google.com"
                "10.0.1.13"]]
      (is (allows? rules ip))
      (is (not (denies? rules ip))))))

(deftest ^{:network true} handles-blocking-ipv6
  (let [rules (define-rules "+i:*, -c:::1/128")]
    (doseq [ip ["::1"]]
      (is (not (allows? rules ip)))
      (is (denies? rules ip)))

    (doseq [ip ["f:f:f:f:f:f:f:f"
                "2620::0:1cfe:face:b00c:0:b"]]
      (is (allows? rules ip))
      (is (not (denies? rules ip))))))
