package models

val MODULE_PERMISSION_LABELS: LinkedHashMap<String, String> = linkedMapOf(
    "units" to "Units",
    "positions" to "Positions",
    "employees" to "Employees",
    "salary" to "Salaries",
    "raw_materials" to "Raw materials",
    "products" to "Products",
    "ingredients" to "Ingredients",
    "purchase" to "Purchase",
    "production" to "Production",
    "sales" to "Sales",
    "budget" to "Budget",
    "users" to "Users"
)

data class ModulePermission(
    val moduleKey: String,
    val label: String,
    val canEdit: Boolean,
    val canDelete: Boolean
)

fun defaultModulePermissions(): LinkedHashMap<String, ModulePermission> {
    val result = LinkedHashMap<String, ModulePermission>()
    for ((key, label) in MODULE_PERMISSION_LABELS) {
        result[key] = ModulePermission(key, label, canEdit = false, canDelete = false)
    }
    return result
}
