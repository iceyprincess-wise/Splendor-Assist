object BlobListResult : java.util.AbstractList<ConnectedComponentEngine.Blob>() {
    internal var pool: Array<ConnectedComponentEngine.Blob> = emptyArray()
    override var size: Int = 0
    override fun get(index: Int): ConnectedComponentEngine.Blob = pool[index]
    fun bind(p: Array<ConnectedComponentEngine.Blob>, s: Int) { pool = p; size = minOf(s, p.size) }
}

