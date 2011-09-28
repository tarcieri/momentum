(ns picard.firewall
  (:import
   [org.jboss.netty.handler.ipfilter
    IpFilterRule
    IpFilterRuleList]
   java.net.InetAddress))

(defn define-rules
  [rules]
  (reverse (IpFilterRuleList. rules)))

(defn allows?
  [rules address]
  (if (string? address)
    (allows? rules (InetAddress/getByName address))
    (loop [rules rules]
      (when-let [current (first rules)]
        (if (.contains current address)
          (.isAllowRule current)
          (recur (rest rules)))))))

(defn denies?
  [rules addr]
  (not (allows? rules addr)))
