package com.jxitc.messagehub.domain.service

/**
 * 通知的连续去重（严格相邻）：与**紧邻的上一条**完全一致的通知只记第一条。
 *
 *   例: A A A → 1 条；A B A → 3 条（B 打断后 A 重新开始，不做跨来源去重）。
 *
 * 线程安全说明（重要）：
 * [com.jxitc.messagehub.service.NotificationListener] 对**每个**通知回调都起一个协程，
 * 一批几乎同时到达的相同通知会并发调用这里。若"读上一跳 → 比较 → 写回"不是原子的，
 * 这些协程会全部读到旧值、全部通过去重（实测：闹钟通知一次入库 7 条相同记录，
 * 详见 tasks.md 3.4.12 / Dedup Issue 1）。因此这里用 [synchronized] 把三步合成一个
 * 临界区，保证同一批里只有第一条被放行。
 *
 * 进程重启后缓存清空 → 最多多记一条，可接受。
 */
class NotificationDeduplicator {

    private val lock = Any()
    private var last: Entry? = null

    private data class Entry(val pkg: String, val title: String, val content: String)

    /**
     * 判断当前通知是否为紧邻上一条的重复。
     *
     * @return true = 与上一条完全相同，应丢弃；false = 放行，并把它记为新的"上一条"。
     */
    fun isDuplicate(packageName: String, title: String, content: String): Boolean =
        synchronized(lock) {
            val prev = last
            if (prev != null &&
                prev.pkg == packageName &&
                prev.title == title &&
                prev.content == content
            ) {
                true
            } else {
                last = Entry(packageName, title, content)
                false
            }
        }
}
