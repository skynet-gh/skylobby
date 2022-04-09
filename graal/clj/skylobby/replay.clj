(ns skylobby.replay)


(set! *warn-on-reflection* true)


(defprotocol ReplayIndex
  (replay-by-id [this replay-id])
  (replay-by-path [this path])
  (update-replay [this replay])
  (update-replays [this replays]))
