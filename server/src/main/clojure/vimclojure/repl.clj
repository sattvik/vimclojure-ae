;-
; Copyright 2009 (c) Meikel Brandmeyer.
; All rights reserved.
;
; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:
;
; The above copyright notice and this permission notice shall be included in
; all copies or substantial portions of the Software.
;
; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
; THE SOFTWARE.

(ns vimclojure.repl
  (:require
    clojure.test)
  (:use
     [vimclojure.util :only (stream->seq pretty-print pretty-print-causetrace)])
  (:import
     (clojure.lang Var Compiler LineNumberingPushbackReader)))

(def
  #^{:doc
  "A map holding the references to all running repls indexed by their repl id."}
  *repls*
  (atom {}))

(let [id (atom 0)]
  (defn repl-id
    "Get a new Repl id."
    []
    (swap! id inc)))

(def
  #^{:doc
  "Set to true in the Repl if you want pretty printed results. Has no effect
  if clojure.contrib.pprint is not available."}
  *print-pretty*
  false)

(defstruct
  #^{:doc
  "The structure for the Repl interface. Holds the state of a Repl between
  invokations. The members correspond to the Vars as bound be with-binding."}
  repl
  :id :ns :warn-on-reflection :print-meta :print-length :print-level
  :compile-path :command-line-args :expr1 :expr2 :expr3 :exception :test-out
  :line)

(defn make-repl
  "Create a new Repl."
  ([id] (make-repl id (the-ns 'user)))
  ([id namespace]
   (struct-map repl
               :id                 id
               :ns                 namespace
               :warn-on-reflection *warn-on-reflection*
               :print-meta         *print-meta*
               :print-length       *print-length*
               :print-level        *print-level*
               :compile-path       (System/getProperty
                                     "clojure.compile.path"
                                     "classes")
               :command-line-args  nil
               :expr1              nil
               :expr2              nil
               :expr3              nil
               :exception          nil
               :print-pretty       vimclojure.repl/*print-pretty*
               :test-out           nil
               :line               0)))

(defn start
  "Start a new Repl and register it in the system."
  [nspace]
  ; Make sure user namespace exists.
  (let [id       (repl-id)
        the-repl (make-repl id nspace)]
    (swap! *repls* assoc id the-repl)
    id))

(defn stop
  "Stop the Repl with the given id."
  [id]
  (when-not (@*repls* id)
    (throw (Exception. "Not Repl of that id or Repl currently active: " id)))
  (swap! *repls* dissoc id)
  nil)

(defn root-cause
  "Drill down to the real root cause of the given Exception."
  [cause]
  (if-let [cause (.getCause cause)]
    (recur cause)
    cause))

(defn make-reader
  "Create a proxy for a LineNumberingsPushbackReader, which delegates
  everything, but allows to specify an offset as initial line."
  [reader offset]
  (proxy [LineNumberingPushbackReader] [reader]
    (getLineNumber [] (+ offset (proxy-super getLineNumber)))))

(defn with-repl*
  "Calls thunk in the context of the Repl with the given id. id may be -1
  to use a one-shot context. Sets the file line accordingly."
  [id nspace file line thunk]
  (let [the-repl (if (not= id -1)
                   (locking *repls*
                     (if-let [the-repl (get @*repls* id)]
                       (do
                         (swap! *repls* dissoc id)
                         the-repl)
                       (throw (Exception. (str "No Repl of that id: " id)))))
                   (make-repl -1))
        line     (if (= line 0)
                   (the-repl :line)
                   line)]
    (with-bindings
      {Compiler/LINE          line
       Compiler/SOURCE        (.getName (java.io.File. file))
       Compiler/SOURCE_PATH   file
       #'*in*                 (make-reader *in* line)
       #'*ns*                 (if nspace nspace (the-repl :ns))
       #'*warn-on-reflection* (the-repl :warn-on-reflection)
       #'*print-meta*         (the-repl :print-meta)
       #'*print-length*       (the-repl :print-length)
       #'*print-level*        (the-repl :print-level)
       #'*compile-path*       (the-repl :compile-path)
       #'*command-line-args*  (the-repl :command-line-args)
       #'*1                   (the-repl :expr1)
       #'*2                   (the-repl :expr2)
       #'*3                   (the-repl :expr3)
       #'*e                   (the-repl :exception)
       #'vimclojure.repl/*print-pretty* (the-repl :print-pretty)
       #'clojure.test/*test-out* (if-let [test-out (the-repl :test-out)]
                                   test-out
                                   *out*)}
      (try
        (thunk)
        (finally
          (when (not= id -1)
            (swap! *repls* assoc id
                   (struct-map
                     repl
                     :id                 id
                     :ns                 *ns*
                     :warn-on-reflection *warn-on-reflection*
                     :print-meta         *print-meta*
                     :print-length       *print-length*
                     :print-level        *print-level*
                     :compile-path       *compile-path*
                     :command-line-args  *command-line-args*
                     :expr1              *1
                     :expr2              *2
                     :expr3              *3
                     :exception          *e
                     :print-pretty       vimclojure.repl/*print-pretty*
                     :test-out           (let [test-out clojure.test/*test-out*]
                                           (when-not (identical? test-out *out*)
                                             test-out))
                     :line               (dec (.getLineNumber *in*))))))))))

(defmacro with-repl
  "Executes body in the context of the Repl with the given id. id may be -1
  to use a one-shot context. Sets the file line accordingly."
  [id nspace file line & body]
  `(with-repl* ~id ~nspace ~file ~line (fn [] ~@body)))

(defn run
  "Reads from *in* and evaluates the found expressions. The state of the
  Repl is retrieved using the given id. Output goes to *out* and *err*.
  The initial input line and the file are set to the supplied values.
  Ignore flags whether the evaluation result is saved in the star Vars."
  [id nspace file line ignore]
  (with-repl id nspace file line
    (try
      (doseq [form (stream->seq *in*)]
        (let [result (eval form)]
          ((if vimclojure.repl/*print-pretty* pretty-print prn) result)
          (when-not ignore
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 result))))
      (catch Throwable e
        (binding [*out* *err*]
          (if (= id -1)
            (pretty-print-causetrace e)
            (println e)))
        (set! *e e)
        nil))))
