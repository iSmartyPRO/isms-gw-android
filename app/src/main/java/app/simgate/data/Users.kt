package app.simgate.data

/**
 * Access permissions resolved for a user (web panel session) or an API key.
 * sims == null means access to all SIMs.
 */
data class Access(
    val user: User,
    val sims: Set<Int>?,
    val canSend: Boolean,
    val canRead: Boolean,
    val canUssd: Boolean = false,
    val canCall: Boolean = false,
) {
    val isAdmin get() = user.isAdmin
    fun allows(simId: Int) = sims == null || simId in sims
}

object Users {
    fun all(): List<User> = ConfigStore.value.users
    fun find(id: String?): User? = all().firstOrNull { it.id == id }
    fun byUsername(name: String): User? = all().firstOrNull { it.username.equals(name.trim(), ignoreCase = true) }
    fun admin(): User = find(User.ADMIN_ID) ?: all().first { it.isAdmin }

    /** Login/password check; null = invalid or the user is disabled. */
    fun verify(username: String, password: String): User? {
        val u = byUsername(username) ?: return null
        if (!u.enabled || (!u.canWeb && !u.isAdmin)) return null
        return if (Crypto.verifyPassword(u.hash, u.salt, password)) u else null
    }

    fun setPassword(id: String, password: String) {
        val (hash, salt) = Crypto.hashPassword(password)
        ConfigStore.update { c ->
            c.copy(
                users = c.users.map { if (it.id == id) it.copy(hash = hash, salt = salt) else it },
                // stop showing the temporary admin password
                admin = if (id == User.ADMIN_ID) c.admin.copy(hash = hash, salt = salt, initialPassword = "") else c.admin,
            )
        }
    }

    fun touchLogin(id: String, ip: String) = ConfigStore.update { c ->
        c.copy(users = c.users.map { if (it.id == id) it.copy(lastLoginAt = System.currentTimeMillis(), lastLoginIp = ip) else it })
    }

    fun access(u: User): Access =
        if (u.isAdmin) Access(u, null, canSend = true, canRead = true, canUssd = true, canCall = true)
        else Access(u, if (u.allSims) null else u.sims.toSet(), u.canSend, u.canRead, u.canUssd, u.canCall)

    /** API key permissions: intersection of the owner's permissions, the key's permissions and its SIM subset. */
    fun accessForKey(k: ApiKey): Access? {
        val owner = find(k.ownerId) ?: return null
        if (!owner.enabled || (!owner.isAdmin && !owner.canUseApi)) return null
        val base = access(owner)
        val keySims = k.sims.toSet().takeIf { it.isNotEmpty() }
        val sims = when {
            keySims == null -> base.sims
            base.sims == null -> keySims
            else -> keySims intersect base.sims
        }
        return Access(owner, sims, base.canSend && "send" in k.scopes, base.canRead && "read" in k.scopes,
            base.canUssd && "ussd" in k.scopes, base.canCall && "call" in k.scopes)
    }

    fun validUsername(name: String) = Regex("^[A-Za-z0-9._-]{3,32}$").matches(name)
}
