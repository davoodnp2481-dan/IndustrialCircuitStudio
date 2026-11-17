package com.indsim.editor

/** نقطه روی شبکه (واحد = یک خانه‌ی شبکه). */
data class GridPt(val x: Int, val y: Int)

/** یک قطعه‌ی قرارگرفته روی نقشه. (x,y) = مبدأ قطعه؛ rot = ۰/۹۰/۱۸۰/۲۷۰ درجه. */
data class Placed(
    val id: String,
    val type: String,
    val x: Int,
    val y: Int,
    val rot: Int = 0,
    val params: Map<String, String> = emptyMap(),
)

/** سیم: اتصال الکتریکی دو پین به شکل "K1:A1". lane = جابه‌جایی خط افقی میانی برای جلوگیری از انطباق سیم‌ها. */
data class Wire(val a: String, val b: String, val lane: Int = 0, val color: Int? = null)

/** سند نقشه. تغییرناپذیر است؛ هر ویرایش یک سند جدید می‌دهد (پایه‌ی Undo/Redo). */
data class Document(
    val name: String = "Untitled",
    val placed: List<Placed> = emptyList(),
    val wires: List<Wire> = emptyList(),
) {
    fun find(id: String): Placed? = placed.firstOrNull { it.id == id }
}
