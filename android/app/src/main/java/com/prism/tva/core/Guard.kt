package com.prism.tva.core

/**
 * Safety boundary. The assistant never presses a payment button and never acts on a login, OTP or
 * password screen: it stops and hands control back to the user.
 */
object Guard {

    /** Buttons that would start or commit a payment (matched on normalised, space-padded labels). */
    private val PAY_ACTIONS = listOf(
        " pay ", " pay now", " place order", " proceed to buy", " proceed to checkout", " proceed to pay",
        " add payment method", " make payment", " confirm order", " confirm and pay", " buy now",
        " complete payment", " slide to pay", " swipe to pay", " pay using", " checkout ",
        // The same buttons with the app in Hindi.
        " भुगतान करें", " अभी भुगतान", " भुगतान जोड़ें", " ऑर्डर करें", " ऑर्डर दें", " अभी खरीदें", " चेकआउट",
    )

    /**
     * Phrases that, two or more together, mark a payment-method screen. Deliberately specific: product
     * pages also mention "EMI", "credit card" and "UPI" in their offers.
     */
    private val PAY_SCREEN = listOf(
        "upi id", "enter upi", "net banking", "netbanking", "select a payment method", "choose a payment method",
        "select payment method", "payment options", "cvv", "card number", "expiry date", "name on card",
        "wallets", "cash on delivery", "pay on delivery", "add new card", "saved cards",
        "bill total", "pay by any upi app", "add credit or debit cards",
    )

    /** Hints or labels of text fields that ask for credentials. */
    private val CRED_FIELDS = listOf(
        "otp", "one time password", "verification code", "enter code", "password", "passcode", "pin",
        "phone number", "mobile number", "email", "e mail", "username",
        "ओटीपी", "पासवर्ड", "मोबाइल नंबर", "फ़ोन नंबर", "फोन नंबर",
    )
    private val AUTH_WORDS = listOf(
        "log in", "login", "sign in", "signin", "sign up", "verify", "continue with", "otp", "welcome back",
        "लॉग इन", "लॉगिन", "साइन इन", "साइन अप", "ओटीपी", "सत्यापित",
    )

    /** Phrases that only appear on a signed-out / sign-in screen. */
    private val LOGIN_SCREEN = listOf(
        "choose your account", "use another sign in method", "log in or sign up", "login or signup", "sign in or sign up",
        "enter your mobile number", "enter your phone number", "continue with google", "continue with phone",
        "लॉग इन करें", "साइन इन करें", "लॉग इन या साइन अप", "अपना मोबाइल नंबर दर्ज करें",
    )

    private fun padded(s: String) = " " + Text.norm(s) + " "

    // The phrases in the same normal form as the screen text (Hindi nukta letters, case), keeping the
    // word-boundary spaces written into them.
    private fun np(x: String) = (if (x.startsWith(" ")) " " else "") + Text.norm(x) + (if (x.endsWith(" ")) " " else "")
    private val PAY_A by lazy { PAY_ACTIONS.map(::np) }
    private val PAY_S by lazy { PAY_SCREEN.map(Text::norm) }
    private val CRED by lazy { CRED_FIELDS.map(Text::norm) }
    private val AUTH by lazy { AUTH_WORDS.map(Text::norm) }
    private val LOGIN by lazy { LOGIN_SCREEN.map(Text::norm) }

    /** Non-null reason if tapping an element with these labels could start a payment. */
    fun actionBlock(labels: Collection<String>): String? {
        for (l in labels) {
            val n = padded(l)
            if (PAY_A.any { n.contains(it) }) return "payment"
        }
        return null
    }

    /** Non-null reason if the current app screen is a payment, login, OTP or password screen. */
    fun screenBlock(snap: Snapshot, pkg: String): String? {
        val nodes = snap.appNodes(pkg)
        if (nodes.any { it.password }) return "password"
        // The app's own name for the screen (e.g. Zomato's PaymentsOptionsActivityV5).
        if (Regex("payment", RegexOption.IGNORE_CASE).containsMatchIn(snap.activity ?: "") && snap.appPkg == pkg) return "payment"
        val screenText = padded(nodes.joinToString(" ") { it.label })
        val payHits = PAY_S.count { screenText.contains(" $it ") }
        if (payHits >= 2) return "payment"
        val credField = nodes.any { n ->
            n.editable && CRED.any { k -> padded(n.hint ?: "").contains(" $k ") || padded(n.label).contains(" $k ") }
        }
        if (credField && AUTH.any { screenText.contains(" $it ") }) return "login"
        // Signed out: a login screen with no field yet (Zomato's "Choose your account · Use another
        // sign-in method", a "Continue with Google" page). Tapping the account would sign in. (Not the
        // activity name: Zomato's splash screen is also com.application.zomato.login.ZomatoActivity.)
        if (LOGIN.any { screenText.contains(" $it ") }) return "login"
        return null
    }

    /** A pay/checkout button is on screen (e.g. Zomato's cart "Add Payment Method"): the next tap would pay. */
    fun atPaymentStep(snap: Snapshot, pkg: String): Boolean =
        screenBlock(snap, pkg) == "payment" ||
            snap.appNodes(pkg).any { n -> n.label.isNotEmpty() && n.bounds.height() < snap.screenH / 4 && actionBlock(listOf(n.label)) != null }

    fun spoken(reason: String) = when (reason) {
        "payment" -> "the payment step"
        "password" -> "a password screen"
        "login" -> "a sign-in screen (you seem to be signed out; please sign in, then ask me again)"
        else -> "a sensitive screen"
    }
}
