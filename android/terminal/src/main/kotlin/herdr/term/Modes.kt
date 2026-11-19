package herdr.term

/**
 * The keyboard modes the program set, as the emulator knew them. A new attach wipes the
 * emulator (modes included) until the core's paint arrives; typing in that gap is encoded
 * against a snapshot taken before the wipe, so a paste stays one bracketed paste and the
 * arrow keys stay the arrows the program asked for.
 */
data class Modes(val bracketedPaste: Boolean = false, val appCursorKeys: Boolean = false) {
    companion object {
        /** Call with the emulator locked. */
        fun of(emu: Emulator) = Modes(emu.bracketedPaste, emu.appCursorKeys)
    }
}
