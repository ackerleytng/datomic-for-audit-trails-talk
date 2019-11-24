(ns datomic-tutorial.core
  (:require [datomic.client.api :as d]))

(def cfg {:server-type :peer-server
          :access-key "myaccesskey"
          :secret "mysecret"
          :endpoint "localhost:8998"
          ;; From https://docs.datomic.com/on-prem/peer-server.html:
          ;; Connection between clients and the Peer Server does not support SSL hostname validation.
          ;; Both should be run within trusted network boundaries.
          :validate-hostnames false})

(def client (d/client cfg))

(def conn (d/connect client {:db-name "hello"}))

;; Schema for the firewall rule entry entity

(def firewall-rule-entry-schema
  [{:db/ident :firewall/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Reference for this rule, like 'magical-unicorn'"
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
    :db/doc "The port to allow traffic through"}])

;; Add firewall-rule-entry-schema into datomic

(d/transact conn {:tx-data firewall-rule-entry-schema})

;; Schema for the user entity

(def user-schema
  [{:db/ident :user/uuid
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "The uuid for this user from SSO service"
    :db/unique :db.unique/identity}])

;; Add user-schema into datomic

(d/transact conn {:tx-data user-schema})

;; Requester information is a property of the transaction

(def change-schema
  [{:db/ident :change/requester
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The requester of this change"}])

;; Set the schema

(d/transact conn {:tx-data change-schema})

;; ----------------------------------------------------
;;  Launch into using the database
;; ----------------------------------------------------

;; Set up some helper functions to get user info from the SSO service

(def sso-user-info
  {#uuid "deadbeef-0000-0000-0000-000000000000" "Alice"
   #uuid "badc0c0a-0000-0000-0000-000000000000" "Bob"
   #uuid "b0bca700-0000-0000-0000-000000000000" "Carol"})

(defn get-user-name-from-sso
  [uuid]
  (sso-user-info uuid))

(defn get-user-uuid-from-sso
  [name]
  (let [rev-mapping (into {} (map (fn [[k v]] [v k]) sso-user-info))]
    (rev-mapping name)))

;; Insert some users

(d/transact
 conn
 {:tx-data (map
            (fn [uuid] {:user/uuid uuid})
            (keys sso-user-info))})

;; Check that users were inserted

(->> (d/q '[:find ?uuid
            :where [_ :user/uuid ?uuid]]
          (d/db conn))
     (map (juxt first (comp get-user-name-from-sso first))))

(d/q '[:find ?user
       :in $ ?user-uuid
       :where
       [?user :user/uuid ?user-uuid]]
     (d/db conn) (get-user-uuid-from-sso "Bob"))

;; Make our first firewall rule entry

(d/transact
 conn
 {:tx-data [{:firewall/name "magical-unicorn"
             :firewall/description "To access jira"
             :firewall/src-ip-cidr "10.33.44.55/32"
             :firewall/dst-ip-cidr "10.44.55.66/32"
             :firewall/dst-port 443}
            ;; Annotate transaction with requester
            {:db/id "datomic.tx"
             :change/requester
             [:user/uuid (get-user-uuid-from-sso "Alice")]}]})

;; Get the latest state of the database

(def db (d/db conn))

;; Retrieve firewall rule entry named "magical-unicorn"
;;   (Pull the entire entity)

(d/pull db '[*] [:firewall/name "magical-unicorn"])

;; Retrieve firewall rule entry with information about person who last changed the entity

(d/q '[:find (pull ?e [*]) ?uuid
       :where
       ;; Nested query to get the latest transaction relating to entity
       [(q '[:find ?e (max ?tx)
             :where
             [?e :firewall/name "magical-unicorn"]
             [?e _ _ ?tx]]
           $) [[?e ?tx]]]
       [?tx :change/requester ?user]
       [?user :user/uuid ?uuid]]
     db)

;; Make function to pull latest state of entity

(def ^:private fw-req-query
  '[:find (pull ?e [*]) ?uuid ?time
    :in $ ?firewall-rule-entry-name
    :where
    ;; Nested query to get the latest transaction relating to entity
    [(q '[:find ?e (max ?tx)
          :in $ ?firewall-rule-entry-name
          :where
          [?e :firewall/name ?firewall-rule-entry-name]
          [?e _ _ ?tx]]
        $ ?firewall-rule-entry-name) [[?e ?tx]]]
    [?tx :change/requester ?user]
    [?user :user/uuid ?uuid]
    [?tx :db/txInstant ?time]])

(d/q fw-req-query db "magical-unicorn")

(defn firewall-rule-entry
  [db firewall-rule-entry-name]
  (let [[entity uuid time]
        (first (d/q fw-req-query db firewall-rule-entry-name))]
    (some-> entity
            (assoc :firewall/last-modified-by (get-user-name-from-sso uuid))
            (assoc :firewall/last-modified time))))

(firewall-rule-entry db "magical-unicorn")

;; Update existing firewall rule entry
;;   (Add entity to database)

(d/transact
 conn
 {:tx-data [{:firewall/name "magical-unicorn"
             ;; Only insert differences
             :firewall/dst-port 8443}
            ;; This time Bob requested the change
            {:db/id "datomic.tx"
             :change/requester [:user/uuid (get-user-uuid-from-sso "Bob")]}]})

;; Get the latest state of the database

(def db-1 (d/db conn))

;; Check that changes took effect

(firewall-rule-entry db-1 "magical-unicorn")

;; The old state of the db was not changed

(firewall-rule-entry db "magical-unicorn")

;; Get this db's history

(def db-history-1 (d/history db-1))

;; Look up all the changes done to "magical-unicorn"

(->> (d/q '[:find ?tx ?time ?requester-uuid ?attr ?val ?op
            :where
            [?e :firewall/name "magical-unicorn"]
            [?e ?attr ?val ?tx ?op]
            [?tx :db/txInstant ?time]
            [?tx :change/requester ?requester]
            [?requester :user/uuid ?requester-uuid]]
          db-history-1)
     (sort-by first)
     (map (fn [[tx time requester-uuid attr val op]]
            [tx time (get-user-name-from-sso requester-uuid) attr val op])))

;; Restore blocking on firewall
;;   (Retract :firewall/dst-port, :firewall/src-ip-cidr, :firewall/dst-ip-cidr)

(d/transact
 conn
 {:tx-data [[:db/retract [:firewall/name "magical-unicorn"] :firewall/dst-port 8443]
            [:db/retract [:firewall/name "magical-unicorn"] :firewall/src-ip-cidr "10.33.44.55/32"]
            [:db/retract [:firewall/name "magical-unicorn"] :firewall/dst-ip-cidr "10.44.55.66/32"]
            {:db/id "datomic.tx"
             :change/requester [:user/uuid (get-user-uuid-from-sso "Alice")]}]})

;; Get the latest state of the database

(def db-2 (d/db conn))

;; Check that changes took effect

(firewall-rule-entry db-2 "magical-unicorn")

;; Get this db's history again

(def db-history-2 (d/history db-2))

;; Look up all the changes done to "magical-unicorn"

(->> (d/q '[:find ?tx ?time ?requester-uuid ?attr ?val ?op
            :where
            [?e :firewall/name "magical-unicorn"]
            [?e ?attr ?val ?tx ?op]
            [?tx :db/txInstant ?time]
            [?tx :change/requester ?requester]
            [?requester :user/uuid ?requester-uuid]]
          db-history-2)
     (sort-by first)
     (map (fn [[tx time requester-uuid attr val op]]
            [tx time (get-user-name-from-sso requester-uuid) attr val op])))

;; ----------------------------------------------------
;;  Complex temporal queries
;; ----------------------------------------------------

;; As of when Bob made the :firewall/dst-port 8443, what was the entity like?

(let [txid (->> (d/q '[:find ?tx
                       :in $ $history ?user-uuid
                       :where
                       [?e :firewall/name "magical-unicorn"]
                       [$history ?e :firewall/dst-port 8443 ?tx]
                       [?requester :user/uuid ?user-uuid]
                       [$history ?tx :change/requester ?requester]]
                     db-2 db-history-2 (get-user-uuid-from-sso "Bob"))
                ffirst)
      db-then (d/as-of db-2 txid)]
  (firewall-rule-entry db-then "magical-unicorn"))

;; Since Bob made the :firewall/dst-port 8443, what changed?

(let [txid (->> (d/q '[:find ?tx
                       :in $ $history ?user-uuid
                       :where
                       [?e :firewall/name "magical-unicorn"]
                       [$history ?e :firewall/dst-port 8443 ?tx]
                       [?requester :user/uuid ?user-uuid]
                       [$history ?tx :change/requester ?requester]]
                     db-2 db-history-2 (get-user-uuid-from-sso "Bob"))
                ffirst)
      db-since (d/since db-2 txid)]
  (->> (d/q '[:find ?tx ?time ?requester-uuid ?attr ?val ?op
              :in $ $since
              :where
              [?e :firewall/name "magical-unicorn"]
              [$since ?e ?attr ?val ?tx ?op]
              [$since ?tx :db/txInstant ?time]
              [$since ?tx :change/requester ?requester]
              [?requester :user/uuid ?requester-uuid]]
            db-2 (d/history db-since))
       (sort-by first)
       (map (fn [[tx time requester-uuid attr val op]]
              [tx time (get-user-name-from-sso requester-uuid) attr val op]))))
