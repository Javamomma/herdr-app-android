package herdr.core

/** One terminal of the core as the find box sees it (agent or not). Plain data, built by the app. */
data class FindItem(
    val address: String,
    /** The program / agent name ("Claude Code", "zsh"). */
    val name: String,
    /** What it says it is doing, or its title. */
    val title: String,
    /** The working directory (full path; matched as typed). */
    val folder: String,
    /** The machine's label, local terminals included. */
    val machine: String,
    /** The space and tab it lives in; null when it has no pane in any tab. */
    val space: String? = null,
    val tab: String? = null,
    val pinned: Boolean = false,
    /** blocked / error / done / working / idle for an agent, null for a plain terminal. */
    val state: String? = null,
)

/** Search over every terminal of the connected core (docs: AGENTS.md, "Find"). */
object Find {
    /** The typed words: split on white space, lower-cased, no empties. */
    fun terms(query: String): List<String> = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun fieldScores(item: FindItem, term: String): Int {
        val name = item.name.lowercase()
        val best = listOf(
            if (name.startsWith(term)) 5 else if (term in name) 4 else 0,
            if (term in item.title.lowercase()) 3 else 0,
            if (term in item.folder.lowercase()) 2 else 0,
            if (term in item.machine.lowercase()) 1 else 0,
        ).max()
        return best
    }

    /** Score of [item] for [terms]: 0 when any word matches nowhere (name, title, folder, machine), else the sum of each word's best field. */
    fun score(item: FindItem, terms: List<String>): Int {
        var sum = 0
        for (t in terms) {
            val s = fieldScores(item, t)
            if (s == 0) return 0
            sum += s
        }
        return sum
    }

    /**
     * The matches, best first. An empty query (or only white space) shows everything in the order given, pinned first.
     * Otherwise higher score first (a name beats a title beats a folder beats a machine), ties in the order given.
     */
    fun search(query: String, items: List<FindItem>): List<FindItem> {
        val terms = terms(query)
        if (terms.isEmpty()) return Pins.order(items) { it.pinned }
        return items.mapIndexedNotNull { i, it -> score(it, terms).takeIf { s -> s > 0 }?.let { s -> Triple(i, s, it) } }
            .sortedWith(compareBy({ -it.second }, { it.first })).map { it.third }
    }

    /** "space › tab" so duplicates can be told apart; a terminal in no tab says so. */
    fun where(item: FindItem): String = where(item.space, item.tab)

    fun where(space: String?, tab: String?): String = when {
        space.isNullOrBlank() && tab.isNullOrBlank() -> "no tab"
        tab.isNullOrBlank() -> space!!
        space.isNullOrBlank() -> tab
        else -> "$space › $tab"
    }
}

/** A top-level row with the helpers listed under it (one visual level). */
data class Branch(val root: String, val helpers: List<String>)

/** Helpers nest under the agent that started them (`parent` of the terminal record). */
object AgentTree {
    /**
     * Builds the rows from [order] (addresses in the usual order: pinned first, then urgency) and [parentOf].
     * A terminal whose parent is not in the list (or empty, or itself) is a top-level row. A helper of a helper goes under
     * the top ancestor, so there is one level. A cycle in bad data ends at its member that comes first in [order], which is
     * shown top-level; nothing loops. Roots keep their order, except that an unpinned root rises to its most urgent
     * helper's place (a pinned helper does not lift its parent into the pinned rows). Helpers keep the order given.
     */
    fun build(order: List<String>, parentOf: (String) -> String?, pinned: (String) -> Boolean = { false }): List<Branch> {
        val index = HashMap<String, Int>()
        order.forEachIndexed { i, a -> index.putIfAbsent(a, i) }
        val items = index.keys
        val top = HashMap<String, String>()
        fun parent(a: String): String? = parentOf(a)?.takeIf { it.isNotEmpty() && it != a && it in items }
        for (start in order) {
            if (start in top) continue
            val path = ArrayList<String>()
            val onPath = HashSet<String>()
            var cur: String? = start
            var found: String? = null
            while (cur != null) {
                val known = top[cur]
                if (known != null) { found = known; break }
                if (!onPath.add(cur)) {
                    // A cycle: its member that comes first in the order is the top.
                    val cycle = path.subList(path.indexOf(cur), path.size)
                    found = cycle.minBy { index.getValue(it) }
                    break
                }
                path.add(cur)
                val p = parent(cur)
                if (p == null) { found = cur; break }
                cur = p
            }
            val t = found ?: start
            for (a in path) top[a] = t
            top[t] = t
        }
        val helpers = LinkedHashMap<String, MutableList<String>>()
        val roots = ArrayList<String>()
        for (a in index.keys.sortedBy { index.getValue(it) }) {
            val t = top.getValue(a)
            if (t == a) { roots.add(a); helpers.getOrPut(a) { ArrayList() } } else helpers.getOrPut(t) { ArrayList() }.add(a)
        }
        fun key(root: String): Int {
            val own = index.getValue(root)
            if (pinned(root)) return own
            return (helpers.getValue(root).filter { !pinned(it) }.map { index.getValue(it) } + own).min()
        }
        return roots.withIndex().sortedWith(compareBy({ key(it.value) }, { it.index })).map { Branch(it.value, helpers.getValue(it.value)) }
    }

    /** States that must always be visible: blocked or error. */
    fun urgent(state: String) = state == "blocked" || state == "error"

    /** Done and unread (the shown state is "done" only while unread): may stay folded if the person folded it. */
    fun unread(state: String) = state == "done"

    /** States that need a look: blocked, error, or done and unread. */
    fun needsAttention(state: String) = urgent(state) || unread(state)

    /**
     * Open when any helper is blocked or in error, whatever was chosen. Otherwise the person's choice, else open when
     * a helper is done and unread.
     */
    fun expanded(chosen: Boolean?, helperStates: List<String>): Boolean =
        helperStates.any(::urgent) || (chosen ?: helperStates.any(::unread))

    fun countText(n: Int): String = if (n == 1) "1 helper" else "$n helpers"

    /** The header under a parent: "3 helpers"; folded it also says what is waiting there ("3 helpers · 1 done"). */
    fun summary(helperStates: List<String>, open: Boolean): String {
        val base = countText(helperStates.size)
        if (open) return base
        val done = helperStates.count(::unread)
        return if (done > 0) "$base · $done done" else base
    }

    /**
     * The `parent` of [address] as an address in the same list. A parent without a machine prefix on a helper that lives on
     * another machine is that machine's own (`t7` for `vps/t9` means `vps/t7`) when [exists] says so; otherwise the value as
     * given. Blank is none.
     */
    fun resolveParent(address: String, raw: String?, exists: (String) -> Boolean): String? {
        val p = raw?.takeIf { it.isNotBlank() } ?: return null
        val machine = machineOf(address)
        if (machine != null && '/' !in p) {
            val own = "$machine/$p"
            if (exists(own)) return own
        }
        return p
    }

    /**
     * The nearest agent above [first] (a helper's parent chain may pass through plain terminals); null when the chain
     * ends or loops without one.
     */
    fun nearestAgent(first: String?, isAgent: (String) -> Boolean, parentOf: (String) -> String?): String? {
        var cur = first
        val seen = HashSet<String>()
        while (!cur.isNullOrEmpty() && seen.add(cur)) {
            if (isAgent(cur)) return cur
            cur = parentOf(cur)
        }
        return null
    }
}
