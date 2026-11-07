package herdr.app

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class HerdrApplication : Application() {
    lateinit var session: Session
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider; sshj needs the full one
        // (Ed25519 keys, curve25519 key exchange).
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        session = Session(this)
    }
}
