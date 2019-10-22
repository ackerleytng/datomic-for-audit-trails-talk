(ns datomic-tutorial.core
  (:require [datomic.client.api :as d]))

(def cfg {:server-type :peer-server
          :access-key "myaccesskey"
          :secret "mysecret"
          :endpoint "localhost:8998"
          :validate-hostnames false})

(def client (d/client cfg))

(def conn (d/connect client {:db-name "hello"}))

(def schema
  [{:db/ident :firewall/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Reference for this rule, like 'bionic-beaver'"
    :db/unique :db.unique/identity}

   {:db/ident :firewall/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "What is this firewall rule for?"}

   {:db/ident :firewall/src-ip-cidr
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "A source ip range, such as 192.0.2.1/24"}

   {:db/ident :firewall/dst-ip-cidr
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "A destination ip range, such as 192.0.3.1/24"}

   {:db/ident :firewall/dst-port
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "The port to allow traffic through"}

   {:db/ident :firewall/requester
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The requester of this change"}])

;; Set the schema

(d/transact conn {:tx-data schema})

;; Insert firewall request
;;   (Add entity to database)

(d/transact
 conn
 {:tx-data [{:firewall/name "bionic-beaver"
             :firewall/description "To access jira"
             :firewall/src-ip-cidr "10.33.44.55/32"
             :firewall/dst-ip-cidr "10.44.55.66/32"
             :firewall/dst-port 443}
            ;; Reified transaction
            ;;   Annotate transaction with requester
            {:db/id "datomic.tx"
             :firewall/requester "Ackerley"}]})

;; Get the latest state of the database

(def db (d/db conn))

;; Retrieve firewall request named "bionic-beaver"
;;   (Pull the entire entity)

(d/pull db '[*] [:firewall/name "bionic-beaver"])

;; Retrieve firewall request with person who last changed the entity

(d/q '[:find (pull ?e [*]) ?requester
       :where
       ;; Nested query to get the latest transaction relating to entity
       [(q '[:find ?e (max ?tx)
             :where
             [?e :firewall/name "bionic-beaver"]
             [?e _ _ ?tx]]
           $) [[?e ?tx]]]
       [?tx :firewall/requester ?requester]]
     db)

;; Make function to pull latest state of entity

(def ^:private fw-req-query
  '[:find (pull ?e [*]) ?requester ?time
    :in $ ?firewall-name
    :where
    ;; Nested query to get the latest transaction relating to entity
    [(q '[:find ?e (max ?tx)
          :where
          [?e :firewall/name ?firewall-name]
          [?e _ _ ?tx]]
        $) [[?e ?tx]]]
    [?tx :firewall/requester ?requester]
    [?tx :db/txInstant ?time]])

(defn firewall-request-entry
  [db firewall-request-name]
  (let [[entity requester time]
        (first (d/q fw-req-query db firewall-request-name))]
    (if (seq entity)
      (-> entity
          (assoc :firewall/last-modified-by requester)
          (assoc :firewall/last-modified time)))))

(firewall-request-entry db "bionic-beaver")

;; Update existing request
;;   (Add entity to database)

(d/transact
 conn
 {:tx-data [{:firewall/name "bionic-beaver"
             ;; Only insert differences
             :firewall/dst-port 8443}
            ;; This time Shane requested the change
            {:db/id "datomic.tx"
             :firewall/requester "Shane"}]})

;; Get the latest state of the database

(def db-1 (d/db conn))

;; Check that changes took effect

(firewall-request-entry db-1 "bionic-beaver")

;; The old state of the db was not changed

(firewall-request-entry db "bionic-beaver")

;; Get this db's history

(def db-history-1 (d/history db-1))

;; Look up all the changes done to "bionic-beaver"

(->> (d/q '[:find ?tx ?time ?requester ?attr ?val ?op
            :where
            [?e :firewall/name "bionic-beaver"]
            [?e ?attr ?val ?tx ?op]
            [?tx :db/txInstant ?time]
            [?tx :firewall/requester ?requester]]
          db-history-1)
     (sort-by first))

;; Disable firewall
;;   (Retract :firewall/dst-port, :firewall/src-ip-cidr, :firewall/dst-ip-cidr)

(d/transact
 conn
 {:tx-data [[:db/retract [:firewall/name "bionic-beaver"] :firewall/dst-port 8443]
            [:db/retract [:firewall/name "bionic-beaver"] :firewall/src-ip-cidr "10.33.44.55/32"]
            [:db/retract [:firewall/name "bionic-beaver"] :firewall/dst-ip-cidr "10.44.55.66/32"]
            {:db/id "datomic.tx"
             :firewall/requester "Ackerley"}]})

;; Get the latest state of the database

(def db-2 (d/db conn))

;; Check that changes took effect

(firewall-request-entry db-2 "bionic-beaver")

;; Get this db's history again

(def db-history-2 (d/history db-2))

;; Look up all the changes done to "bionic-beaver"

(->> (d/q '[:find ?tx ?time ?requester ?attr ?val ?op
            :where
            [?e :firewall/name "bionic-beaver"]
            [?e ?attr ?val ?tx ?op]
            [?tx :db/txInstant ?time]
            [?tx :firewall/requester ?requester]]
          db-history-2)
     (sort-by first))

;; As of when Shane made the :firewall/dst-port 8443, what was the entity like?

(let [txid (->> (d/q '[:find ?tx ?e
                       :in $ $history
                       :where
                       [$ ?e :firewall/name "bionic-beaver"]
                       [$history _ :firewall/dst-port 8443 ?tx]
                       [$history ?tx :firewall/requester "Shane"]]
                     db-2 (d/history db-2))
                ffirst)
      db-then (d/as-of db-2 txid)]
  (firewall-request-entry db-then "bionic-beaver"))

;; Since Shane made the :firewall/dst-port 8443, what changed?

(let [txid (->> (d/q '[:find ?tx ?e
                       :in $ $history
                       :where
                       [$ ?e :firewall/name "bionic-beaver"]
                       [$history _ :firewall/dst-port 8443 ?tx]
                       [$history ?tx :firewall/requester "Shane"]]
                     db-2 (d/history db-2))
                ffirst)
      db-since (d/since db-2 txid)]
  (->> (d/q '[:find ?tx ?time ?requester ?attr ?val ?op
              :in $ $since
              :where
              [$ ?e :firewall/name "bionic-beaver"]
              [$since ?e ?attr ?val ?tx ?op]
              [$since ?tx :db/txInstant ?time]
              [$since ?tx :firewall/requester ?requester]]
            db-2 (d/history db-since))
       (sort-by first)))
