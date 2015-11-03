(* Author: Tobias Nipkow *)

section \<open>A 2-3 Tree Implementation of Maps\<close>

theory Tree23_Map
imports
  Tree23_Set
  Map_by_Ordered
begin

fun lookup :: "('a::linorder * 'b) tree23 \<Rightarrow> 'a \<Rightarrow> 'b option" where
"lookup Leaf x = None" |
"lookup (Node2 l (a,b) r) x =
  (if x < a then lookup l x else
  if a < x then lookup r x else Some b)" |
"lookup (Node3 l (a1,b1) m (a2,b2) r) x =
  (if x < a1 then lookup l x else
   if x = a1 then Some b1 else
   if x < a2 then lookup m x else
   if x = a2 then Some b2
   else lookup r x)"

fun upd :: "'a::linorder \<Rightarrow> 'b \<Rightarrow> ('a*'b) tree23 \<Rightarrow> ('a*'b) up\<^sub>i" where
"upd x y Leaf = Up\<^sub>i Leaf (x,y) Leaf" |
"upd x y (Node2 l ab r) =
   (if x < fst ab then
        (case upd x y l of
           T\<^sub>i l' => T\<^sub>i (Node2 l' ab r)
         | Up\<^sub>i l1 ab' l2 => T\<^sub>i (Node3 l1 ab' l2 ab r))
    else if x = fst ab then T\<^sub>i (Node2 l (x,y) r)
    else
        (case upd x y r of
           T\<^sub>i r' => T\<^sub>i (Node2 l ab r')
         | Up\<^sub>i r1 ab' r2 => T\<^sub>i (Node3 l ab r1 ab' r2)))" |
"upd x y (Node3 l ab1 m ab2 r) =
   (if x < fst ab1 then
        (case upd x y l of
           T\<^sub>i l' => T\<^sub>i (Node3 l' ab1 m ab2 r)
         | Up\<^sub>i l1 ab' l2 => Up\<^sub>i (Node2 l1 ab' l2) ab1 (Node2 m ab2 r))
    else if x = fst ab1 then T\<^sub>i (Node3 l (x,y) m ab2 r)
    else if x < fst ab2 then
             (case upd x y m of
                T\<^sub>i m' => T\<^sub>i (Node3 l ab1 m' ab2 r)
              | Up\<^sub>i m1 ab' m2 => Up\<^sub>i (Node2 l ab1 m1) ab' (Node2 m2 ab2 r))
         else if x = fst ab2 then T\<^sub>i (Node3 l ab1 m (x,y) r)
         else
             (case upd x y r of
                T\<^sub>i r' => T\<^sub>i (Node3 l ab1 m ab2 r')
              | Up\<^sub>i r1 ab' r2 => Up\<^sub>i (Node2 l ab1 m) ab2 (Node2 r1 ab' r2)))"

definition update :: "'a::linorder \<Rightarrow> 'b \<Rightarrow> ('a*'b) tree23 \<Rightarrow> ('a*'b) tree23" where
"update a b t = tree\<^sub>i(upd a b t)"

fun del :: "'a::linorder \<Rightarrow> ('a*'b) tree23 \<Rightarrow> ('a*'b) up\<^sub>d"
where
"del x Leaf = T\<^sub>d Leaf" |
"del x (Node2 Leaf ab1 Leaf) = (if x=fst ab1 then Up\<^sub>d Leaf else T\<^sub>d(Node2 Leaf ab1 Leaf))" |
"del x (Node3 Leaf ab1 Leaf ab2 Leaf) = T\<^sub>d(if x=fst ab1 then Node2 Leaf ab2 Leaf
  else if x=fst ab2 then Node2 Leaf ab1 Leaf else Node3 Leaf ab1 Leaf ab2 Leaf)" |
"del x (Node2 l ab1 r) = (if x<fst ab1 then node21 (del x l) ab1 r else
  if x > fst ab1 then node22 l ab1 (del x r) else
  let (ab1',t) = del_min r in node22 l ab1' t)" |
"del x (Node3 l ab1 m ab2 r) = (if x<fst ab1 then node31 (del x l) ab1 m ab2 r else
  if x = fst ab1 then let (ab1',m') = del_min m in node32 l ab1' m' ab2 r else
  if x < fst ab2 then node32 l ab1 (del x m) ab2 r else
  if x = fst ab2 then let (ab2',r') = del_min r in node33 l ab1 m ab2' r'
  else node33 l ab1 m ab2 (del x r))"

definition delete :: "'a::linorder \<Rightarrow> ('a*'b) tree23 \<Rightarrow> ('a*'b) tree23" where
"delete x t = tree\<^sub>d(del x t)"


subsection \<open>Functional Correctness\<close>

lemma lookup: "sorted1(inorder t) \<Longrightarrow> lookup t x = map_of (inorder t) x"
by (induction t) (auto simp: map_of_simps split: option.split)


lemma inorder_upd:
  "sorted1(inorder t) \<Longrightarrow> inorder(tree\<^sub>i(upd a b t)) = upd_list a b (inorder t)"
by(induction t) (auto simp: upd_list_simps split: up\<^sub>i.splits)

corollary inorder_update:
  "sorted1(inorder t) \<Longrightarrow> inorder(update a b t) = upd_list a b (inorder t)"
by(simp add: update_def inorder_upd)


lemma inorder_del: "\<lbrakk> bal t ; sorted1(inorder t) \<rbrakk> \<Longrightarrow>
  inorder(tree\<^sub>d (del x t)) = del_list x (inorder t)"
by(induction t rule: del.induct)
  (auto simp: del_list_simps inorder_nodes del_minD split: prod.splits)

corollary inorder_delete: "\<lbrakk> bal t ; sorted1(inorder t) \<rbrakk> \<Longrightarrow>
  inorder(delete x t) = del_list x (inorder t)"
by(simp add: delete_def inorder_del)


subsection \<open>Balancedness\<close>

lemma bal_upd: "bal t \<Longrightarrow> bal (tree\<^sub>i(upd a b t)) \<and> height(upd a b t) = height t"
by (induct t) (auto split: up\<^sub>i.split)(* 30 secs in 2015 *)

corollary bal_update: "bal t \<Longrightarrow> bal (update a b t)"
by (simp add: update_def bal_upd)


lemma height_del: "bal t \<Longrightarrow> height(del x t) = height t"
by(induction x t rule: del.induct)
  (auto simp add: heights max_def height_del_min split: prod.split)

lemma bal_tree\<^sub>d_del: "bal t \<Longrightarrow> bal(tree\<^sub>d(del x t))"
by(induction x t rule: del.induct)
  (auto simp: bals bal_del_min height_del height_del_min split: prod.split)

corollary bal_delete: "bal t \<Longrightarrow> bal(delete x t)"
by(simp add: delete_def bal_tree\<^sub>d_del)


subsection \<open>Overall Correctness\<close>

interpretation T23_Map: Map_by_Ordered
where empty = Leaf and lookup = lookup and update = update and delete = delete
and inorder = inorder and wf = bal
proof (standard, goal_cases)
  case 2 thus ?case by(simp add: lookup)
next
  case 3 thus ?case by(simp add: inorder_update)
next
  case 4 thus ?case by(simp add: inorder_delete)
next
  case 6 thus ?case by(simp add: bal_update)
next
  case 7 thus ?case by(simp add: bal_delete)
qed simp+

end
