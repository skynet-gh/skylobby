(ns spring-lobby.lua
  (:import
    (org.luaj.vm2 LuaValue)
    (org.luaj.vm2.lib.jse JsePlatform)))


(set! *warn-on-reflection* true)


(def mocks
  "VFS = {}

function VFS.DirList(dir, fileType)
    return {}
end

function getfenv()
    local t = {}
    t[\"mapinfo\"] = {}
    return t
end

Spring = {}

function Spring.Log(x, level, message)
    return {}
end

")


(defn table-to-map
  "Returns a map from the given lua table, with keys converted to keywowrds and inner table values
  converted to maps as well."
  [^LuaValue lv]
  (let [table (.checktable lv)]
    (loop [m {}
           prevk LuaValue/NIL]
      (let [kv (.next table prevk)
            k (.arg1 kv)
            v (.arg kv 2)]
        (if (.isnil k)
          m
          (recur
            (let [kk (keyword (.toString k))
                  vs (cond
                       (.istable v) (table-to-map v)
                       :else
                       (.toString v))]
              (assoc m kk vs))
            k))))))

(defn read-mapinfo
  "Returns a map repsenting mapinfo from the given string representation of mapinfo.lua."
  [s]
  (let [globals (JsePlatform/standardGlobals)
        lua-chunk (.load globals (str mocks s))
        res (.call lua-chunk)]
    (table-to-map res)))

(defn read-modinfo
  "Returns a map repsenting modinfo from the given string representation of modinfo.lua."
  [s]
  (let [globals (JsePlatform/standardGlobals)
        lua-chunk (.load globals (str mocks s))
        res (.call lua-chunk)]
    (table-to-map res)))
