(ns datomic-tutorial.core
  (:require [datomic.client.api :as d]
            [clojure.pprint :as pp]))

(def cfg {:server-type :peer-server
          :access-key "myaccesskey"
          :secret "mysecret"
          :endpoint "localhost:8998"
          :validate-hostnames false})

(def client (d/client cfg))

(def conn (d/connect client {:db-name "hello"}))

(d/transact conn {:tx-data [{:db/ident :red}
                            {:db/ident :green}
                            {:db/ident :blue}
                            {:db/ident :yellow}]})

(defn make-idents
  [x]
  (mapv #(hash-map :db/ident %) x))

(def sizes [:small :medium :large :x-large])

(make-idents sizes)

(def colors [:red :green :blue :yellow])

(def types [:shirt :pants :dress :hat])

(d/transact conn {:tx-data (make-idents sizes)})
(d/transact conn {:tx-data (make-idents types)})

(def schema-1
  [{:db/ident :inv/sku
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :inv/color
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :inv/size
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :inv/type
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

(d/transact conn {:tx-data schema-1})

(def sample-data
  (->> (for [color colors size sizes type types]
         {:inv/color color
          :inv/size size
          :inv/type type})
       (map-indexed
        (fn [idx map]
          (assoc map :inv/sku (str "SKU-" idx))))
       vec))

(d/transact conn {:tx-data sample-data})

(def db (d/db conn))

(d/pull db
        [{:inv/color [:db/ident]}
         {:inv/size [:db/ident]}
         {:inv/type [:db/ident]}]
        [:inv/sku "SKU-42"])

(d/q '[:find ?sku
       :where
       [?e :inv/sku "SKU-42"]
       [?e :inv/color ?color]
       [?e2 :inv/color ?color]
       [?e2 :inv/sku ?sku]]
     db)

(def order-schema
  [{:db/ident :order/items
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true}
   {:db/ident :item/id
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :item/count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(d/transact conn {:tx-data order-schema})

(def add-order
  {:order/items
   [{:item/id [:inv/sku "SKU-25"]
     :item/count 10}
    {:item/id [:inv/sku "SKU-26"]
     :item/count 20}]})

(d/transact conn {:tx-data [add-order]})

(def db (d/db conn))

(d/q '[:find ?sku
       :in $ ?inv
       :where
       [?item :item/id ?inv]
       [?order :order/items ?item]
       [?order :order/items ?other-item]
       [?other-item :item/id ?other-inv]
       [?other-inv :inv/sku ?sku]]
     db [:inv/sku "SKU-25"])

(def rules
  '[[(ordered-together ?inv ?other-inv)
     [?item :item/id ?inv]
     [?order :order/items ?item]
     [?order :order/items ?other-item]
     [?other-item :item/id ?other-inv]]])

(d/q '[:find ?sku
       :in $ % ?inv
       :where
       (ordered-together ?inv ?other-inv)
       [?other-inv :inv/sku ?sku]]
     db rules [:inv/sku "SKU-25"])

(def inventory-counts
  [{:db/ident :inv/count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(d/transact conn {:tx-data inventory-counts})

(def inventory-update
  [[:db/add [:inv/sku "SKU-21"] :inv/count 7]
   [:db/add [:inv/sku "SKU-22"] :inv/count 7]
   [:db/add [:inv/sku "SKU-42"] :inv/count 100]])

(d/transact conn {:tx-data inventory-update})

(d/transact
 conn
 {:tx-data [[:db/retract [:inv/sku "SKU-22"] :inv/count 7]
            [:db/add "datomic.tx" :db/doc "remove incorrect assertion"]]})

(d/transact
 conn
 {:tx-data [[:db/add [:inv/sku "SKU-42"] :inv/count 1000]
            [:db/add "datomic.tx" :db/doc "correct data entry error"]]})

(def db (d/db conn))

(d/q '[:find ?sku ?count
       :where
       [?inv :inv/sku ?sku]
       [?inv :inv/count ?count]]
     db)

(def txid (->> (d/q '[:find (max 3 ?tx)
                      :where [?tx :db/txInstant]]
                    db)
               first
               first
               last))

(def db-before (d/as-of db txid))

(d/q '[:find ?sku ?count
       :where
       [?inv :inv/sku ?sku]
       [?inv :inv/count ?count]]
     db-before)

(def db-history (d/history db))

(->> (d/q '[:find ?tx ?sku ?val ?op
            :where
            [?inv :inv/count ?val ?tx ?op]
            [?inv :inv/sku ?sku]]
          db-history)
     (sort-by first))
