(ns spring-lobby.lua
  (:require
    [clj-antlr.core :as antlr]
    [clojure.java.io :as io]))


(def lua-parser
  (future (antlr/parser "lib/grammars-v4/lua/Lua.g4")))


(defn parse [s]
  (lua-parser s))

#_
(parse (slurp "mapinfo.lua"))
