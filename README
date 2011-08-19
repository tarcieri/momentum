## What is Picard?

Picard is a high performance asynchronous networking framework built for the Clojure programming language. Picard is still very young and currently only supports HTTP, however there are plans to make it more generic for arbitrary network programming.

Currently, Picard provides:
  * An HTTP server abstraction
  * An HTTP client abstraction
  * An HTTP proxy abstraction
  * A connection pool for client HTTP connections.

Yes, the README is still quite lacking, but more will come. I just wanted to get this out to the world.

## Usage

First, require the picard namespace. Then:

       (picard/start-server
        (fn [downstream]
            (fn [evt val]
                (when (= :request evt)
                      (downstream :response [200 {"content-length" "5"} "Hello"])))))

More details will come, but for now, there are fairly extensive tests.

## Todo

* More documentation
* A lot of refactoring
* A generic networking abstraction
* A future abstraction
* A lot more goodness

## Why the Java?

Well, this was my first Clojure project. I'm still getting used to the idioms. Also, my use cases for this was fairly performance sensitive, and some things, I couldn't quite figure out how to do efficiently in Clojure.

## Why not Aleph?

 I definitely checked out Aleph & Lamina before writing Picard. I think that Aleph is really a great project and I learned a lot reading the source to it. I wrote Picard for a few reasons. First of all, Aleph was a lot less mature when I first started; it has evolved and grown a lot since. Second, Aleph / Lamina has no mechanism to handle slow consumers. While this might sound as a minor concern, this has fairly wide spread repercussions. Third, I wanted a base abstraction that was  as light as possible. Aleph's base abstraction is a channel, which is fairly heavy weight. Picard's base abstraction is a function and closures.
