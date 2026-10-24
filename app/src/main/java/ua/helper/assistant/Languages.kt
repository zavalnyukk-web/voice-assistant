package ua.helper.assistant

/** Мови для розпізнавання мовлення й озвучення. */
object Languages {
    class Lang(val tag: String, val label: String, val adjective: String, val sample: String)

    val all = listOf(
        Lang("uk-UA", "Українська", "українською", "Привіт! Я Арчік, ваш голосовий помічник."),
        Lang("en-US", "English", "англійською", "Hello! I'm Archik, your voice assistant."),
        Lang("pl-PL", "Polski", "польською", "Cześć! Jestem Archik, twój asystent głosowy."),
        Lang("de-DE", "Deutsch", "німецькою", "Hallo! Ich bin Archik, dein Sprachassistent."),
        Lang("es-ES", "Español", "іспанською", "¡Hola! Soy Archik, tu asistente de voz."),
        Lang("fr-FR", "Français", "французькою", "Salut ! Je suis Archik, ton assistant vocal.")
    )

    fun find(tag: String): Lang = all.firstOrNull { it.tag == tag } ?: all[0]
}
