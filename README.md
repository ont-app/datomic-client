# ont-app/datomic-client

Ports the [Datomic
Client](https://docs.datomic.com/cloud/client/client-api.html) to the
[IGraph](https://github.com/ont-app/igraph) protocols.

Part of the ont-app library, dedicated to Ontology-driven development.

## Contents
- [Dependencies](#h2-dependencies)
- [Connecting to the Datomic database](#h2-connecting-to-a-datomic-database)
- [Creating a graph](#h2-creating-a-graph)
- [Member access](#h2-member-access)
  - [S-P-O vs E-A-V](#h3-spo-vs-eav)
    - [Minting KWSs](#h4-minting-kwis)
  - [Querying](#h3-querying)
  - [Other utilities](#h3-other-utilities)
- [Adding and removing members](#h2-adding-and-removing-members)
  - [Adding with `claim`](#h3-adding-with-claim)
  - [Removing with `retract`](#h3-removing-with-retract)
- [Testing](#h2-testing)


<a name="h2-dependencies"></a>
## Dependencies

Follow the [getting started guide for
Datomic](https://docs.datomic.com/on-prem/get-datomic.html). To get a
datomic service up and running.

Leiningen:

```
[com.datomic/client-pro "0.9.41"]
[ont-app/datomic-client "0.1.0-SNAPSHOT"]
[ont-app/igraph "0.1.4-SNAPSHOT"]
[ont-app/igraph-vocabulary "0.1.0-SNAPSHOT"]

```

Require as:

```
(ns my.ns
  (:require 
     [datomic.client.api :as d]
     [environ.core :refer [env]]
     [ont-app.datomic-client.core :as dg]
     [ont-app.igraph.core :as igraph :refer :all]
     [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
    )
```

<a name="h2-connecting-to-a-datomic-database"></a>
## Connecting to the Datomic database

See the [Datomic docs on connecting to a
database](https://docs.datomic.com/on-prem/getting-started/connect-to-a-database.html). This
will give you instructions for installing and running Datomic.

For purposes of this discussion, let's assume that you have a datomic
service set up on $DATOMIC_HOST : $DATOMIC_PORT, using a database
named $DATOMIC_DB_NAME with $DATOMIC_ACCESS_KEY AND $DATOMIC_SECRET.

Somewhere at the top level of your application you will acquire a
datomic connection per the Datomic instructions:

```
(def cfg {:server-type :peer-server
          :access-key (env :datomic-access-key) 
          ;; ... "myaccesskey"
          :secret (env :datomic-secret) 
          ;; ... "mysecret"
          :endpoint (str (env :datomic-host) ":" (env :datomic-port))
          ;; ... "localhost:8998"
          :validate-hostnames false})

(def client (d/client cfg))
(def conn (d/connect client {:db-name (env :datomic-db-name)}))

```

<a name="h2-creating-a-graph"></a>
## Creating a graph

Graph instances are created with `make-graph`, with two arities.  This
function returns an instance of
`ont_app.datomic_client.core.DatomicClient`, with members `:conn` and
`:db`:

With just the connection (db will be set as of the most recent transaction):
```
> (def g (make-graph conn))
{:conn
 {:db-name "hello", :database-id "5e4608e8-8e48-49e5-a43f-9b73168ca4b8", :t 1034, :next-t 1040, :type :datomic.client/conn},
 :db
 {:t 1034, :next-t 1040, :db-name "hello", :database-id "5e4608e8-8e48-49e5-a43f-9b73168ca4b8", :as-of 1034, :type :datomic.client/db}}
>
```

Or we can provide a `db` argument as of any previous transaction:
```
> (def db-on-valentines-day  (d/as-of (d/db conn) #inst "2020-02-14")) 
> (def g-on-valentines-day (make-graph conn db-on-valentines-day))
{:conn
 {:db-name "hello", :database-id "5e4608e8-8e48-49e5-a43f-9b73168ca4b8", :t 1034, :next-t 1040, :type :datomic.client/conn},
 :db
 {:t 1034, :next-t 1040, :db-name "hello", :database-id "5e4608e8-8e48-49e5-a43f-9b73168ca4b8", :as-of #inst "2020-02-14T00:00:00.000-00:00", :type :datomic.client/db}}
> 
```

A new database comes with a set of [standard
schema](https://docs.datomic.com/cloud/schema/schema-reference.html)
declarations.

The following supporting attributes are automatically added to those
schema declarations:

```
> dg/igraph-schema
[#:db{:ident :igraph/kwi,
      :valueType :db.type/keyword,
      :unique :db.unique/identity,
      :cardinality :db.cardinality/one,
      :doc "Uniquely names a graph element"}
 #:db{:ident :igraph/edn?,
      :valueType :db.type/boolean,
      :cardinality :db.cardinality/one,
      :doc
      "Domain is string-valued property. True if value should be encoded and 
      read as an edn representation of some object."}]
>
``` 

<a name="h2-member-access"></a>
## Member access 

We can access the Datomic native representation directly:

```
> (:conn g)
{:db-name "hello", :database-id "5e4608e8-8e48-49e5-a43f-9b73168ca4b8", :t 1034, :next-t 1040, :type :datomic.client/conn}
>
> (:db g)
{:t 1034, :next-t 1040, :db-name "hello", :database-id "5e4608e8-8e48-49e5-a43f-9b73168ca4b8", :as-of 1034, :type :datomic.client/db}
>
```

Each graph implements the [IGraph protocol](https://github.com/ont-app/igraph/tree/develop#The_IGraph_protocol).

We can access the contents of the graph with the standard [IGraph
accessor
functions](https://github.com/ont-app/igraph/tree/develop#Member_access).

We can get a lazy sequence of all subjects:

```
> (subjects g)
(:db/code
 :db.sys/reId
 :ig-ctest/isa
 :db.entity/preds
 :db/tupleAttrs
 ...
 )
 > 
 ```
 Zero args gives us [normal form](https://github.com/ont-app/igraph/tree/develop#Normal_form):
 
 ```
 > (g) 
{:db.type/instant
 {:db/doc
  #{"Value type for instants in time. Stored internally as a number of milliseconds since midnight, January 1, 1970 UTC. Representation type will vary depending on the language you are using."},
  :db/ident #{:db.type/instant},
  :fressian/tag #{:inst}},
 :db/excise
 #:db{:cardinality #{:db.cardinality/one},
      :ident #{:db/excise},
      :valueType #{:db.type/ref}},
 :db.type/tuple
 {:fressian/tag #{:list}, :db/ident #{:db.type/tuple}},
... 
}
>
```

The atom `dg/normal-form-timeout` sets the timeout when querying for
normal form, and defaults to 1000 (ms). If the timeout is exceeded it
will throw _ex-info_ of type `::igraph/Intractable`.

One arg gives us a description of a subject _s_:

```
> (g :db/code) ;; returns description of :db/code in normal form
 #:db{:cardinality #{:db.cardinality/one},
     :fulltext #{true},
     :doc
     #{"String-valued attribute of a data function that contains the function's source code."},
     :ident #{:db/code},
     :valueType #{:db.type/string}}
>
```

Two args gives us the set of objects for _s_ and _p_:

```
> (g :db/code :db/doc) ;; returns set of objects
#{"String-valued attribute of a data function that contains the function's source code."}
>
(unique (g :db/code :db/doc)) ;; because we know :db/doc is cardinality-1
"String-valued attribute of a data function that contains the function's source code."
>
```

Three args is truthy:

```
(g :db/code :db/cardinality :db.cardinality/one) ;; truthy
true
>
```

As with all IGraph implementations, a
[traversal](https://github.com/ont-app/igraph/tree/develop#Traversal)
function may be [provided as the `p`
argument](https://github.com/ont-app/igraph/tree/develop#traversal-fn-as-p) for 2- and 3- arg invocations.

<a name="h3-spo-vs-eav"></a>
### S-P-O vs E-A-V

While both IGraph and Datomic's native representation are
graph-oriented, the IGraph representation is based on an RDF-inspired
subject-predicate-object (SPO) model, whereas the Datomic
representation is based on what is sometimes called an
entity-attribute-value (EAV) model.


Entities (E) in this case are DB-specific integer identifiers, whereas
Subjects (S) are assumed to be keyword identifiers (KWIs), used as
(and [mappable to](https://github.com/ont-app/vocabulary))
[URI](https://www.wikidata.org/wiki/Q61694)s. Subjects 'pivot' off of
Entity numbers via attributes which are either
[:db/ident](https://docs.datomic.com/on-prem/identity.html#idents) or
:db/unique
[:db.unique/identity](https://docs.datomic.com/on-prem/identity.html#unique-identities). :db/ident
provides faster access, but should be used relatively sparingly for
large models, as they must be held always in memory.

The `entity-id` function returns the `e` for any unique ID in a given DB:

```
> (dg/entity-id (:db g) :db/cardinality)
41
>
```

You may find the discussion
[below](https://github.com/ont-app/datomic-client#minting-kwis) on the
method for minting KWIs useful.

Attributes (A) and Properties (P) are approximately equivalent. They
must be declared in the schema as :db/ident.

Datomic Values (V) and IGraph Objects (O) are also approximately
equivalent. Values can be either refs or literal values, interpreted
per the Attribute declarations in the Datomic schema. Objects can be
specified as KWIs (interpreted as Datomic :db.type/refs), as literal
values supported by Datomic, or if the object is not supported
natively by Datomic, they may be encoded/decoded as (non-queriable
except by regex) EDN strings.


<a name="h4-minting-kwis"></a>
#### Minting KWIs

The
[ont-app.igraph-vocabulary.core](https://github.com/ont-app/igraph-vocabulary)
module provides a multi-method called `mint-kwi`. This can be useful
for creating unique keyword identifiers to serve as subjects or
objects in your graph. The default behavior is thus:

```
> (mint-kwi :myNs/Head :myNs/p1 "foo" :myNs/p2 "bar")
:myNs/Head_p1_foo_p2_bar
>
``` 

... where `p1` and `p2` should be sufficient to uniquely
distinguish your instance of :myNs/Head in whatever universe you
expect to be playing in.

The `mint-kwi` multi-method is dispatched on the value of the first
'head' argument. Here is an example of a defmethod that mints a KWI
for movies based on the title and the year of release:

```
> (defmethod mint-kwi :movie/Movie
  [head-kwi & args]
  ;; Generates unique KWI for <title> made in <year>
  (let [{title :movie/title
         year :movie/year
         } args
        _ns (namespace head-kwi)
        _name (name head-kwi)
        stringify (fn [x]
                    (cond (string? x) (str/replace x #" " "_")
                          (keyword? x) (name x)
                          :default (str x))) 
        kwi (keyword _ns (str _name "_" (str/join "_"
                                                  [(stringify title)
                                                   (or year "NoDate")])))
        ]
    kwi))
#multifn[mint-kwi 0x1caf7807]
>
> (mint-kwi :movie/Movie 
    :movie/title "The Goonies"
    :movie/year 1985)
:movie/Movie_The_Goonies_1985
>
```

<a name="h3-querying"></a>
#### Querying

The IGraph `query` method can be used for datalog queries with either
a vector for simple queries or a map for datomic's
[arity-one](https://docs.datomic.com/client-api/datomic.client.api.html#var-q)
format:

```
> (query g 
  '[:find ?e  ?v
   :where [?e :db/ident ?v]])
[[22 :db.type/long]
 [38 :db.unique/identity]
 [19 :db.alter/attribute]
 ...
 ]
>
> (query g
  {:query '[:find ?e ?v
           :in $ ?a
           :where [?e ?a ?v]]
   :args [(:db g) :db/ident]
   :limit 3
   })
[[22 :db.type/long]
 [38 :db.unique/identity]
 [19 :db.alter/attribute]]
   
```

Or if you want to use multiple-arity queries or Datomic's [pull
syntax](https://docs.datomic.com/client-api/datomic.client.api.html#var-pull),
you can access the db with (:db g) and do that directly.

<a name="h3-other-utilities"></a>
### Other utilities (specific to this library)

The `domain-element?` function returns true for refs which are not
part of the standard schema, and thus presumably part of your domain
model:

```
> (filter dg/domain-element? (subjects g))
(
 :movie/date
 :movie/title
 :movie/certifications
 :movie/genre
 :movie/Movie_The_Goonies_1985
 ...
 )
> 

```
<a name="h2-adding-and-removing-members"></a>
## Adding and removing members

Datomic describes its mutability model as "[accumulate
only](https://docs.datomic.com/cloud/whatis/data-model.html#accumulate-only)". This
means that you can access any earlier state of the DB using the
`as-of` function. Assertions can be added and removed using the
datomic API functions `add` and `retract`. Assertions which have been
retracted at some point in time are still available to earlier states
of the DB.

This library implements an IGraph mutability model dedicated to this:

```
> (mutability g)
:ont-app.igraph.core/accumulate-only
>
> (satisfies? igraph/IGraphAccumulateOnly g)
true
>
```

<a name="h3-adding-with-claim></a>
### Adding with 'claim'
As it happens, `add` is already dedicated to the
[igraph/IGraphImmutable](https://github.com/ont-app/igraph/tree/develop#IGraphImmutable)
protocol, so the
[igraph/IGraphAccumulateOnly](https://github.com/ont-app/igraph/tree/develop#IGraphAccumulateOnly)
protocol uses `retract` to remove assertions, and its antonym `claim`
to add assertions.

`claim` implements the
[igraph/add-to-graph](https://github.com/ont-app/igraph/tree/develop#add-to-graph)
multimethod, and arguments can be in any of its associated `triples-format`s.

Any given instance of the graph is immutable...

```
> (def g' (claim g [:john :fullName "John Smith"]))
#'user/g'
> 
> (g :john)
nil
>
> (g' :john)
{:fullName #{"John Smith"}}
>
```

Since in this example :fullName is not already in the schema, its
schema is automatically inferred based on the type of its object, with
default cardinality of 'many':

```
> (g' :fullName)
#:db{:doc #{"Declared automatically while importing"},
     :cardinality #{:db.cardinality/many},
     :ident #{:fullName},
     :valueType #{:db.type/string}}
>
```

Keywords are presumed to be KWIs, and interpreted as
:db.type/ref. Properties that range over keywords need to be declared
explicitly in the schema.

If an object is provided whose data type is not [supported natively by
Datomic](https://docs.datomic.com/cloud/schema/schema-reference.html),
it is stored as an EDN string, and read back in when retrieved:

```
> (def g' (claim g' [:john :hasVector [1 2 3]]))
#user/g'
> 
> (unique (g' :john :hasVector))
[1 2 3]
> 
> (g' :hasVector)
{:db/doc #{"Declared automatically while importing"},
 :db/ident #{:hasVector},
 :db/cardinality #{:db.cardinality/many},
 :igraph/edn? #{true},
 :db/valueType #{:db.type/string}}
>
```

However, since these values are stored as strings, our ability to
query against such objects is limited. The example above was provided
as an illustration, but in practice it would often make more sense to
declare it as :db.type/tuple with a :db/tupleType as :db.type/long.


<a name="h3-removing-with-retract"></a>
### Removing with `retract`

The `retract` method implements the
[remove-from-graph](https://github.com/ont-app/igraph/tree/develop#remove-from-graph)
multimethod, dispatched on igraph/triples-removal-format.

```
> (def g' (retract g' [:john]))
#user/g
>
> (g' :john)
nil
>
```

And we can also call retract with more specific _s p_ or _s p o_ arguments.

<a name="h2-testing"></a>
## Testing

The `ont-app.datomic-client.core-test` module requires that you have a
datomic service set up on $DATOMIC_HOST : $DATOMIC_PORT, using a
database named $DATOMIC_DB_NAME with $DATOMIC_ACCESS_KEY AND
$DATOMIC_SECRET, for which see the [datomic
documentation](https://docs.datomic.com/on-prem/getting-started/connect-to-a-database.html).

It will test to ensure that the pertinent examples in
[IGraph](https://github.com/ont-app/igraph)'s README work, as well as
functions specific to _datomic-client_.

## License

Copyright © 2020 Eric D. Scott

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
