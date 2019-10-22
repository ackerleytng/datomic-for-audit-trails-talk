(ns datomic-tutorial.core
  (:require [datomic.client.api :as d]))

(def cfg {:server-type :peer-server
          :access-key "myaccesskey"
          :secret "mysecret"
          :endpoint "localhost:8998"
          :validate-hostnames false})

(def client (d/client cfg))

(def conn (d/connect client {:db-name "hello"}))

(def movie-schema
  [{:db/ident :movie/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The title of the movie"}

   {:db/ident :movie/genre
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "The genre of the movie"}

   {:db/ident :movie/release-year
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "The year the movie was released in theatres"}])

(d/transact conn {:tx-data movie-schema})

(def first-movies [{:movie/title "The Goonies"
                    :movie/genre "action/adventure"
                    :movie/release-year 1985}
                   {:movie/title "Commando"
                    :movie/genre "action/adventure"
                    :movie/release-year 1985}
                   {:movie/title "Repo Man"
                    :movie/genre "punk dystopia"
                    :movie/release-year 1984}])

(d/transact conn {:tx-data first-movies})

(def db (d/db conn))

(def all-movies-q '[:find ?e
                    :where [?e :movie/title]])

(d/q all-movies-q db)

(def all-titles-q '[:find ?movie-title
                    :where [_ :movie/title ?movie-title]])

(d/q all-titles-q db)

(def titles-from-1985 '[:find ?movie-title
                        :where
                        [?e :movie/release-year 1985]
                        [?e :movie/title ?movie-title]])

(d/q titles-from-1985 db)

(def all-data-from-1985 '[:find ?title ?year ?genre
                          :where
                          [?e :movie/release-year 1985]
                          [?e :movie/title ?title]
                          [?e :movie/genre ?genre]
                          [?e :movie/release-year ?year]])

(d/q all-data-from-1985 db)

(def commando-id
  (ffirst (d/q '[:find ?e
                 :where [?e :movie/title "Commando"]]
               db)))

(d/transact conn {:tx-data [{:db/id commando-id :movie/genre "future governor"}]})

(d/q all-data-from-1985 db)

(def db (d/db conn))

(d/q all-data-from-1985 db)

(def old-db (d/as-of db 1004))

(d/q all-data-from-1985 old-db)

(def db-history (d/history db))

(d/q '[:find ?genre
       :where
       [?e :movie/title "Commando"]
       [?e :movie/genre ?genre]] db-history)
