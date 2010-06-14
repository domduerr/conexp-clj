;; Copyright (c) Daniel Borchmann. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

;; This file has been written by Immanuel Albrecht, with modifications by DB

(ns conexp.contrib.gui.editors.context-editor.util
  (:import [javax.swing JSplitPane JRootPane JTextArea JTable JList JTree
             JScrollPane JPanel JButton]
           [java.awt GridLayout BorderLayout Dimension]
           [javax.swing.tree DefaultTreeModel DefaultMutableTreeNode]
           [java.util Vector]
           [javax.swing.table DefaultTableModel])
  (:use conexp.contrib.gui.plugins.base
        conexp.contrib.gui.util
        [conexp.base :exclude (join)]
        conexp.util.hookable
        conexp.util.one-to-many
        conexp.util.typecheck
        clojure.contrib.swing-utils
        conexp.contrib.gui.editors.util
        conexp.fca
        [clojure.contrib.string :only (join split-lines split)]))

;; Helper functions

(defn string-to-cross
  "Takes a string and decides, whether it should be a cross or not."
  [s]
  (let [trimmed (.trim s)]
    (if (re-matches #"0*[,.]?0*" trimmed)
      false
      true)))

(defn smart-str
  "Takes a single argument and returns it as a string that is usable
   for object and attribute names."
  [x]
  (cond
   (or (vector? x)
       (seq? x))
   (join " " (map smart-str x)),
   (set? x)
   (str "{" (join ", " (map smart-str (vec x))) "}"),
   :else
   (str x)))

(defn map-to-unique-strings
  "Takes a sequence of (unique) keys and returns a map
   that maps each unique key to a unique string."
  [keys]
  (loop [k     (set keys),
         m     {},
         taken #{},
         nbr   0]
    (if (empty? k)
      m
      (let [k1        (first k),
            k1-cvrt   (smart-str k1),
            k1-as-str (if (< 0 nbr)
                        (join "-" [k1-cvrt (str nbr)])
                        k1-cvrt)]
        (if (contains? taken k1-as-str)
          (recur k m taken (+ 1 nbr))
          (recur (disj k k1)
                 (conj m {k1 k1-as-str})
                 (conj taken k1-as-str) 0))))))

(defn req-unique-string
  "Takes a sequence of keys and a new requested key and
  returns a new key that will not conflict with any of the
  keys in the sequence."
  [old-keys req-key]
  (let [keys (set old-keys)]
    (loop [nbr 0]
      (let [new-key (if (< 0 nbr)
                      (join "-" [(str req-key) (str nbr)])
                      (str req-key))]
        (if (contains? keys new-key)
          (recur (+ 1 nbr))
          new-key)))))

(defn switch-bipartit-auto
  "Takes a bipartit auto map and removes the old-key and old-value
   associations and associates new-key with new-value and new-value
   with new-key, then returns the new map."
  [m old-key old-value new-key new-value]
  (let [m-without-old (dissoc m old-key old-value),
        m-with-new    (assoc m-without-old new-key new-value new-value new-key)]
    m-with-new))


;;; Context editor control

(defrecord context-editor-widget [widget table toolbar e-ctx])
(derive ::context-editor-widget :conexp.contrib.gui.editors.util/widget)

(declare make-editable-context editable-context? add-widget set-context
         get-ectx)

(defmulti get-context
  "Returns the fca-context that belongs to the first parameter."
  (fn [& x]
    (class-to-keyword (type (first x))))
  :default nil)

(defmulti get-table
  "Returns the table-control that belongs to the first parameter."
  (fn [& x]
    (class-to-keyword (type (first x))))
  :default nil)

(defn-typecheck get-selected-objects ::context-editor-widget
  "Returns the set of selected objects in the context-editor-widget."
  [widget]
  (let [table (get-table widget),
        view  (get-row-index-permutator table),
        sel   (seq (.getSelectedRows (get-control table))),
        ectx  (get-ectx widget),
        names (deref (:obj-rows ectx)),
        fltr  #(filter (fn [x]
                         (when-not (nil? x)
                           x))
                       %)]
    (set (fltr (map (*comp view names) sel)))))

(defn-typecheck get-selected-attributes ::context-editor-widget
  "Returns the set of selected attributes in the context-editor-widget."
  [widget]
  (let [table (get-table widget),
        view  (get-column-index-permutator table),
        sel   (seq (.getSelectedColumns (get-control table))),
        ectx  (get-ectx widget),
        names (deref (:attr-cols ectx)),
        fltr  #(filter (fn [x]
                         (when-not (nil? x)
                           x))
                       %)]
    (set (fltr (map (*comp view names) sel)))))

(defn- cut-attributes
  "Cut out the given attributes from the context."
  [ctx atts]
  (let [a (filter (comp not (set atts))
                  (attributes ctx))]
    (make-context (objects ctx) a (incidence ctx))))

(defn- cut-objects
  "Cut out the given objects from the context."
  [ctx objs]
  (let [o (filter (comp not (set objs))
                  (objects ctx))]
    (make-context o (attributes ctx) (incidence ctx))))

(defn- cut-objects-attributes
  "Cut out the given objects and attributes from the context"
  [ctx objs atts]
  (cut-attributes (cut-objects ctx objs) atts))

(defn- keep-attributes
  "Cut out all but the given attributes from the context."
  [ctx atts]
  (let [a (intersection (set atts)
                        (attributes ctx))]
    (make-context (objects ctx) a (incidence ctx))))

(defn- keep-objects
  "Cut out all but the given objects from the context."
  [ctx objs]
  (let [o (intersection (set objs)
                        (objects ctx))]
    (make-context o (attributes ctx) (incidence ctx))))

(defn- keep-objects-attributes
  "Cut out the given objects and attributes from the context"
  [ctx objs atts]
  (keep-attributes (keep-objects ctx objs) atts))

(defn- add-new-attribute
  [ctx]
  (let [att (attributes ctx),
        a   (req-unique-string att "new attribute") ]
    (make-context (objects ctx) (conj att a) (incidence ctx))))

(defn- add-new-object
  [ctx]
  (let [obj (objects ctx),
        o   (req-unique-string obj "new object")]
    (make-context (conj obj o) (attributes ctx) (incidence ctx))))

(defn- add-ctx-map-btn
  "Helper that will create the according button vector"
  [f ref-to-ectx txt icon]
  (vec (list add-button
             (make-button txt
                          icon
                          [set-handler
                           (fn []
                             (let [ectx (deref ref-to-ectx),
                                   ctx  (get-context ectx)]
                               (set-context ectx (f ctx))))]))))

(declare get-dual-order)

(defn- add-ctx-map-btn-dual
  "Helper that will create the according button vector"
  [f ref-to-ectx txt icon]
  (vec (list add-button
             (make-button txt
                          icon
                          [set-handler
                           (fn []
                             (let [ectx (deref ref-to-ectx),
                                   ctx  (get-context ectx)]
                               (set-context-keep-order ectx (f ctx) (get-dual-order ectx))))]))))

(defn- add-ctx-map-btn-att
  "Helper that will create the according button vector"
  [f ref-to-widget txt icon]
  (vec (list add-button
             (make-button txt
                          icon
                          [set-handler
                           (fn []
                             (let [widget (deref ref-to-widget),
                                   ectx   (get-ectx widget)
                                   ctx    (get-context ectx)]
                               (set-context ectx
                                            (f ctx (get-selected-attributes widget)))))]))))

(defn- add-ctx-map-btn-obj
  "Helper that will create the according button vector"
  [f ref-to-widget txt icon]
  (vec (list add-button
             (make-button txt
                          icon
                          [set-handler
                           (fn []
                             (let [widget (deref ref-to-widget),
                                   ectx   (get-ectx widget),
                                   ctx    (get-context ectx)]
                               (set-context ectx
                                            (f ctx (get-selected-objects widget)))))]))))

(defn- add-ctx-map-btn-obj-att
  "Helper that will create the according button vector"
  [f ref-to-widget txt icon]
  (vec (list add-button
             (make-button txt
                          icon
                          [set-handler
                           (fn []
                             (let [widget (deref ref-to-widget),
                                   ectx   (get-ectx widget),
                                   ctx    (get-context ectx)]
                               (set-context ectx
                                            (f ctx
                                               (get-selected-objects widget)
                                               (get-selected-attributes widget)))))]))))

(let [second-operand (ref (make-context #{} #{} []))]

  (defn- add-ctx-map-btn-2nd-op
    "Helper that will create the according button vector"
    [f ref-to-ectx txt icon]
    (vec (list add-button
               (make-button txt
                            icon
                            [set-handler
                             (fn []
                               (let [ectx (deref ref-to-ectx),
                                     ctx  (get-context ectx)]
                                 (set-context ectx
                                              (f ctx (deref second-operand)))))]))))

  (defn get-current-second-operand-context
    []
    (deref second-operand))

  (defn-swing make-context-editor-widget
    "Creates a control for editing contexts. Here setup is an optional
    number of vectors that may contain additional tweaks that are
    called after widget creation"
    [& setup]
    (let [self  (promise),
          root  (JRootPane.),
          table (make-table-control [set-row-count 1] [set-column-count 1]),
          ectx  (ref (make-editable-context)),

          toolbar (make-toolbar-control :vert
                     [set-floatable false]
                     [add-button
                       (make-button "Copy"
                         (get-ui-icon "OptionPane.informationIcon")
                         [set-handler #(copy-to-clipboard table)])]
                     [add-button
                       (make-button "Paste"
                         (get-ui-icon "OptionPane.informationIcon")
                         [set-handler #(paste-from-clipboard table)])]
                     [add-button
                       (make-button "Second Operand"
                         (get-ui-icon "OptionPane.informationIcon")
                         [set-handler #(dosync
                                        (ref-set second-operand
                                                 (get-context (deref ectx))))])]
                     [add-separator]
                     (add-ctx-map-btn add-new-attribute ectx "New attribute" nil)
                     (add-ctx-map-btn add-new-object ectx "New object" nil)
                     [add-separator]
                     (add-ctx-map-btn-att keep-attributes self "Keep attributes" nil)
                     (add-ctx-map-btn-obj-att keep-objects-attributes self "Keep both" nil)
                     (add-ctx-map-btn-obj keep-objects self "Keep objects" nil)
                     [add-separator]
                     (add-ctx-map-btn-att cut-attributes self "Cut attributes" nil)
                     (add-ctx-map-btn-obj-att cut-objects-attributes self "Cut both" nil)
                     (add-ctx-map-btn-obj cut-objects self "Cut objects" nil)
                     [add-separator]
                     (add-ctx-map-btn clarify-attributes ectx "Clarify attributes" nil)
                     (add-ctx-map-btn clarify-context ectx "Clarify context" nil)
                     (add-ctx-map-btn clarify-objects ectx "Clarify objects" nil)
                     [add-separator]
                     (add-ctx-map-btn reduce-context-attributes ectx "Reduce attributes" nil)
                     (add-ctx-map-btn reduce-context ectx "Reduce context" nil)
                     (add-ctx-map-btn reduce-context-objects ectx "Reduce objects" nil)
                     [add-separator]
                     (add-ctx-map-btn context-transitive-closure ectx "Transitive closure" nil)
                     [add-separator]
                     (add-ctx-map-btn-dual dual-context ectx "Dual context" nil)
                     (add-ctx-map-btn invert-context ectx "Inverse context" nil)
                     [add-separator]
                     (add-ctx-map-btn-2nd-op context-sum ectx "Context sum..." nil)
                     (add-ctx-map-btn-2nd-op context-product ectx "Context product..." nil)
                     (add-ctx-map-btn-2nd-op context-semiproduct ectx "Context semiproduct..." nil)
                     (add-ctx-map-btn-2nd-op context-xia-product ectx "Context Xia's product..." nil)
                     (add-ctx-map-btn-2nd-op context-union ectx "Context union..." nil)
                     (add-ctx-map-btn-2nd-op context-intersection ectx "Context intersection..." nil)
                     (add-ctx-map-btn-2nd-op context-composition ectx "Context composition..." nil)
                     (add-ctx-map-btn-2nd-op context-apposition ectx "Context apposition..." nil)
                     (add-ctx-map-btn-2nd-op context-subposition ectx "Context subposition..." nil)
                     ),

          e-ctx  @ectx
          widget (context-editor-widget. root table toolbar ectx)]
      (deliver self widget)
      (.. root getContentPane
          (add (get-widget toolbar) BorderLayout/LINE_START))
      (.. root getContentPane
          (add (get-widget table)))
      (apply-exprs widget setup)
      (dosync (alter (:widgets e-ctx) add widget))
      (add-widget e-ctx widget)
      widget))

  nil)

(defn-typecheck get-ectx ::context-editor-widget
  "Returns the editable-context that is currently associated with the
   context-editor-widget."
  [widget]
  @(:e-ctx widget))

(defmethod get-context ::context-editor-widget
  [widget]
  (get-context @(:e-ctx widget)))

(defn-typecheck set-ectx ::context-editor-widget
  "Sets the editable-context that is currently associated with the
   context-editor-widget to the second parameter."
  [widget e-ctx]
  (dosync (ref-set (:e-ctx widget) e-ctx)))

(defmethod get-table ::context-editor-widget
  [widget]
  (:table widget))

(defmethod get-table :conexp.contrib.gui.editors.util/table-control
  [x] x)

(declare change-attribute-name change-object-name change-incidence-cross)

(defn- ectx-cell-value-hook
  [ectx row column contents]
  (cond
   (= column row 0) "⇊objects⇊",
   (= row 0)        (let [attrib-cols  @(:attr-cols ectx),
                          current-name (attrib-cols column)]
                      (if (= contents current-name)
                        current-name
                        (change-attribute-name ectx column contents))),
   (= column 0)     (let [object-rows  @(:obj-rows ectx),
                          current-name (object-rows row)]
                      (if (= contents current-name)
                        current-name
                        (change-object-name ectx row contents))),
   :else            (let [cross         (string-to-cross contents),
                          obj-name      (@(:obj-rows ectx) row),
                          attr-name     (@(:attr-cols ectx) column),
                          fca-ctx       (get-context ectx),
                          inc           (incidence fca-ctx),
                          current-state (contains? inc [obj-name attr-name])]
                      (if (not= current-state cross)
                        (change-incidence-cross ectx obj-name attr-name cross))
                      (if cross "X" " "))))

(defn- ectx-extend-rows-hook
  [ectx rows]
  (let [current-rows (count (objects (get-context ectx)))]
    (call-many @(:widgets ectx)
               (fn [w] (set-row-count (get-table w) rows)))
    (doseq [r (range (+ 1 current-rows) rows)]
      (call-first @(:widgets ectx)
                  (fn [w]
                    (set-value-at-index (get-table w) r 0 "new object"))))))

(defn- ectx-extend-columns-hook
  [ectx cols]
  (let [current-cols (count (attributes (get-context ectx)))]
    (call-many @(:widgets ectx)
               (fn [w] (set-column-count (get-table w) cols)))
    (doseq [c (range (+ 1 current-cols) cols)]
      (call-first @(:widgets ectx)
                  (fn [w]
                    (set-value-at-index (get-table w) 0 c "new attribute"))))))


;;; Editable contexts

(defrecord editable-context [context attr-cols obj-rows widgets])

(defn editable-context?
  "Tests whether the argument is an editable context."
  [ctx]
  (isa? (class-to-keyword (type ctx)) ::editable-context))

(defmethod get-context ::editable-context
  [ctx]
  (deref (:context ctx)))

(defmethod  get-context :conexp.fca.contexts/Context
  [x] x)

(defn make-context-compatible
  "Takes a context object and returns a compatible fca-Context."
  [ctx]
  (let [ctx (get-context ctx),
        obj (objects ctx),
        att (attributes ctx),
        inc (incidence ctx),
        obj-map (map-to-unique-strings obj),
        att-map (map-to-unique-strings att),
        comp-obj (map obj-map obj),
        comp-att (map att-map att),
        comp-inc (map (fn [x] [(obj-map (first x)) (att-map (second x))]) inc)]
    (make-context comp-obj comp-att comp-inc)))

(defn- restore-order
  "Takes obj-rows as a parameter and a corresponding list of object names
   that shall take their respective position if available."
  [old-obj-rows old-objs new-obj-rows obj-nbrs]
  (let [new-obj-keys (set (filter string? (keys new-obj-rows)))]
    (loop [ obj-rows new-obj-rows
            old-objs (filter (fn [x] (contains? new-obj-keys x)) old-objs) ]
      (if (empty? old-objs)
        obj-rows
        (let [obj    (first old-objs),
              target (old-obj-rows obj),
              source (obj-rows obj)]
          (if (and (contains? obj-nbrs target)
                   (not (= source target)))
            (let [obj2           (obj-rows target),
                  other-obj-rows (dissoc obj-rows obj target obj2 source)
                  switched-rows (conj other-obj-rows
                                      {obj target, target obj,
                                       obj2 source, source obj2})]
              (recur switched-rows (rest old-objs)))
            (recur obj-rows (rest old-objs))))))))

(defn make-editable-context
  "Takes an optional context as input and returns an appropriate
  editable-context structure that is bound to a compatible version of
  the input context."
  ([]
     (make-editable-context (make-context '() '() [])))
  ([context-in]
    (let [ctx (make-context-compatible context-in),
          att (attributes ctx),
          att-sort (sort (seq att)),
          obj (objects ctx),
          obj-sort (sort (seq obj)),
          self (promise),
          e-ctx (editable-context. (ref ctx)
                                   (ref (conj (zipmap att-sort (range 1 (+ 1 (count att-sort))))
                                              (zipmap (range 1 (+ 1 (count att-sort))) att-sort)))
                                   (ref (conj (zipmap obj-sort (range 1 (+ 1 (count obj-sort))))
                                              (zipmap (range 1 (+ 1 (count obj-sort))) obj-sort)))
                                   (ref (make-one-to-many self)))]
      (deliver self e-ctx)
      e-ctx))
  ([context-in keep-order]
    (let [e-ctx (make-editable-context context-in),
          old-obj-rows (:obj-rows keep-order),
          old-objs (filter string? (keys old-obj-rows)),
          old-attr-cols (:attr-cols keep-order),
          old-atts (filter string? (keys old-attr-cols)),
          other-obj-rows (deref (:obj-rows e-ctx)),
          obj-nbrs (set (filter (comp not string?)
                                (keys other-obj-rows)))
          other-attr-cols (deref (:attr-cols e-ctx))
          att-nbrs (set (filter (comp not string?)
                                (keys other-attr-cols))),
          new-obj-rows (restore-order old-obj-rows old-objs
                                      other-obj-rows obj-nbrs),
          new-attr-cols (restore-order old-attr-cols old-atts
                                       other-attr-cols att-nbrs)]
      (dosync
       (ref-set (:obj-rows e-ctx) new-obj-rows)
       (ref-set (:attr-cols e-ctx) new-attr-cols))
      e-ctx)))

(defn-typecheck get-order ::editable-context
  "Returns the current order of the objects and attributes of the context."
  [ectx]
  {:attr-cols (deref (:attr-cols ectx)),
   :obj-rows (deref (:obj-rows ectx))})

(defn-typecheck get-dual-order ::editable-context
  "Returns the current order of the objects and attributes of the dual of the
   context."
  [ectx]
  {:obj-rows (deref (:attr-cols ectx)),
   :attr-cols (deref (:obj-rows ectx))})

(defn-typecheck add-widget ::editable-context
  "Adds a context-editor-widget to an editable context, and sets the
   editor windows table to represent the new context."
  [e-ctx editor]
  (let [ctx (get-context e-ctx),
        att-cols (:attr-cols e-ctx),
        obj-rows (:obj-rows e-ctx),
        att (attributes ctx),
        obj (objects ctx),
        inc (incidence ctx),
        get-cross (fn [obj att]
                    (if (contains? inc [obj att]) "X" " "))
        table (get-table editor),
        current-ectx (get-ectx editor)]
    (dosync
     (alter (:widgets current-ectx) del editor)
     (set-ectx editor e-ctx)
     (alter (:widgets e-ctx) add editor))
    (set-hook table "cell-value" (fn [_ _ x] x))
    (set-column-count table (+ 1 (count att)))
    (set-row-count table (+ 1 (count obj)))
    (doseq [a att]
      (set-value-at-index table 0 (att-cols a) a))
    (doseq [o obj]
      (set-value-at-index table (obj-rows o) 0 o))
    (doseq [a att o obj]
      (set-value-at-index table (obj-rows o) (att-cols a)
                          (get-cross o a)))
    (set-value-at-index table 0 0 "⇊objects⇊")
    (set-hook table "extend-rows-to"
              #(ectx-extend-rows-hook e-ctx %))
    (set-hook table "extend-columns-to"
              #(ectx-extend-columns-hook e-ctx %))
    (set-hook table "cell-value"
              (fn [r c s]
                (ectx-cell-value-hook e-ctx r c s)))))

(defn-typecheck set-context-keep-order ::editable-context
  "Sets the bound fca-context of an editable context object to ctx."
  [ectx ctx keep-order]
  (let [new     (make-editable-context ctx keep-order),
        widgets @(:widgets ectx)]
    (dosync
     (ref-set (:widgets ectx) @(:widgets new))
     (ref-set (:context ectx) @(:context new))
     (ref-set (:attr-cols ectx) @(:attr-cols new))
     (ref-set (:obj-rows ectx) @(:obj-rows new)))
    (call-many widgets #(add-widget ectx %))))

(defn-typecheck set-context ::editable-context
  "Sets the bound fca-context of an editable context object to ctx."
  [ectx ctx]
  (set-context-keep-order ectx ctx (get-order ectx)))

(defn-typecheck change-incidence-cross ::editable-context
  "Sets or unsets the cross in the incidence relation of the
  editable-context."
  [ectx obj att cross]
  (let [ostr (if (= (type obj) String)
               obj
               (@(:obj-rows ectx) obj)),
        astr (if (= (type att) String)
               att
               (@(:attr-cols ectx) att))]
    (dosync
      (alter (:context ectx)
             (fn [x]
               (let [as (attributes x),
                     os (objects x),
                     ir (incidence x)]
                 (if cross
                   (make-context os as (conj ir [ostr astr]))
                   (make-context os as (disj ir [ostr astr])))))))
    (let [row (@(:obj-rows ectx) ostr),
          col (@(:attr-cols ectx) astr)]
      (call-many @(:widgets ectx)
                 (fn [w]
                   (update-value-at-index (get-table w)
                                          row
                                          col
                                          (if cross "X" " ")))))))

(defn-typecheck change-attribute-name ::editable-context
 "Changes the attributes name of given to requested, if requested is
  not taken by another attribute. In this case, -# with a unique # is
  appended to the requested name. Returns the new attribute name."
  [ectx given requested]
  (let [att-cols @(:attr-cols ectx)]
    (if (= (type given) String)
      (change-attribute-name ectx (att-cols given) requested)
      (dosync
        (let [ctx           (get-context ectx),
              current-name  (att-cols given),
              attribs       (attributes ctx),
              other-attribs (disj attribs current-name),
              new-name (req-unique-string other-attribs requested)]
          (when-not (= current-name new-name)
            (let [amap #(if (= % current-name) new-name %)]
              (alter (:context ectx)
                     (fn [x]
                       (let [as (conj (disj (attributes x) current-name)
                                      new-name),
                             os (objects x)
                             ir (map (fn [x] [(first x)(amap (second x))])
                                     (incidence x))]
                         (make-context os as ir))))
              (alter (:attr-cols ectx) switch-bipartit-auto
                     given current-name given new-name)
              (call-many @(:widgets ectx)
                         (fn [w]
                           (update-value-at-index (get-table w)
                                                  0
                                                  given new-name)))))
          new-name)))))

(defn-typecheck change-object-name ::editable-context
 "Changes the object's name of given to requested, if requested is
  not taken by another object. In this case, -# with a unique # is
  appended to the requested name. Returns the new attribute name."
  [ectx given requested]
  (let [obj-cols @(:obj-rows ectx)]
    (if (= (type given) String)
      (change-object-name ectx (obj-cols given) requested)
      (dosync
        (let [ctx          (get-context ectx)
              current-name (obj-cols given)
              objs         (objects ctx)
              other-objs   (disj objs current-name)
              new-name     (req-unique-string other-objs requested)]
          (when-not (= current-name new-name)
            (let [omap #(if (= % current-name) new-name %)]
              (alter (:context ectx)
                     (fn [x]
                       (let [os (conj (disj (objects x) current-name)
                                      new-name),
                             as (attributes x)
                             ir (map (fn [x] [(omap (first x))(second x)])
                                     (incidence x))]
                         (make-context os as ir))))
              (alter (:obj-rows ectx) switch-bipartit-auto
                     given current-name given new-name)
              (call-many @(:widgets ectx)
                         (fn [w]
                           (update-value-at-index (get-table w)
                                                  given 0 new-name)))))
          new-name)))))

;;;

nil