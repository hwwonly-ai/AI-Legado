package io.legado.app.model.ai

import io.legado.app.data.entities.BookReadAloudRole

data class AiDialogRoleContext(
    val roles: List<Role>,
    val nameCandidates: List<String>
) {

    data class Role(
        val name: String,
        val aliases: List<String>,
        val roleCategory: String,
        val roleAttribute: String,
        val locked: Boolean,
        val systemRole: Boolean
    )

    companion object {
        val EMPTY = AiDialogRoleContext(emptyList(), emptyList())

        fun fromRoles(
            roles: List<BookReadAloudRole>,
            nameCandidates: List<String>
        ): AiDialogRoleContext {
            return AiDialogRoleContext(
                roles = roles.mapNotNull { role ->
                    val name = runCatching { role.name }.getOrNull()?.trim().orEmpty()
                    if (name.isBlank()) return@mapNotNull null
                    Role(
                        name = name,
                        aliases = runCatching { role.aliases }.getOrNull()
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            .orEmpty(),
                        roleCategory = runCatching { role.roleCategory }.getOrNull()
                            ?.takeIf { it in BookReadAloudRole.RoleCategory.ORDER }
                            ?: BookReadAloudRole.RoleCategory.MINOR_SUPPORTING,
                        roleAttribute = runCatching { role.roleAttribute }.getOrNull()
                            ?.takeIf { it in BookReadAloudRole.RoleAttribute.ALL }
                            ?: BookReadAloudRole.RoleAttribute.MIDDLE_MALE,
                        locked = runCatching { role.locked }.getOrDefault(false),
                        systemRole = runCatching { role.systemRole }.getOrDefault(false)
                    )
                },
                nameCandidates = nameCandidates.map { it.trim() }.filter { it.isNotBlank() }
            )
        }
    }
}
