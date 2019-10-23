# Exploring datomic for audit trails

## Start datomic

This runs datomic with no persistent storage

```
bin/run -m datomic.peer-server -h localhost -p 8998 -a myaccesskey,mysecret -d hello,datomic:mem://hello
```