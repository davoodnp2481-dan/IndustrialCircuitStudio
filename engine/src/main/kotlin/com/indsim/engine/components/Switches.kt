package com.indsim.engine.components

import com.indsim.engine.Branch
import com.indsim.engine.Component
import com.indsim.engine.Impedance

/**
 * شستی با شماره‌گذاری ترمینال IEC: NO=13-14 و NC=21-22.
 * latching = شستی قارچی اضطراری: با فشردن قفل می‌شود و فقط با unlatch() (چرخاندن) آزاد می‌شود.
 */
class PushButton(
    id: String,
    val normallyOpen: Boolean,
    val latching: Boolean = false,
) : Component(id) {
    override val pinNames = if (normallyOpen) listOf("13", "14") else listOf("21", "22")

    var pressed = false
        private set

    val closed: Boolean get() = if (normallyOpen) pressed else !pressed

    fun press() { pressed = true }
    fun release() { if (!latching) pressed = false }
    fun unlatch() { pressed = false }

    private val imp = Impedance(pin(pinNames[0]), pin(pinNames[1]), CONTACT_R)

    override fun branches(): List<Branch> = if (closed) listOf(imp) else emptyList()
}
