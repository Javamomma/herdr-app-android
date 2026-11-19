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

    /** States that need a look: blocked, error, or done and unread (the shown state is "done" only while unread). */
    fun needsAttention(state: String) = state == "blocked" || state == "error" || state == "done"

    /** Open when the person chose so, else when any helper needs attention. */
    fun expanded(chosen: Boolean?, helperStates: List<String>): Boolean = chosen ?: helperStates.any(::needsAttention)

    fun countText(n: Int): String = if (n == 1) "1 helper" else "$n helpers"
}
