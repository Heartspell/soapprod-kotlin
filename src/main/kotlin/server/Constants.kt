package server

internal enum class SessionPermission { EDIT, DELETE }

internal const val SALE_MARKUP = 0.30
internal const val MODULE_PRODUCTION_REQUESTS = "production_requests"
internal const val MODULE_UNITS = "units"
internal const val MODULE_POSITIONS = "positions"
internal const val MODULE_EMPLOYEES = "employees"
internal const val MODULE_SALARY = "salary"
internal const val MODULE_RAW_MATERIALS = "raw_materials"
internal const val MODULE_PRODUCTS = "products"
internal const val MODULE_INGREDIENTS = "ingredients"
internal const val MODULE_PURCHASE = "purchase"
internal const val MODULE_PRODUCTION = "production"
internal const val MODULE_SALES = "sales"
internal const val MODULE_BUDGET = "budget"
internal const val MODULE_USERS = "users"

internal val CREDIT_BANKS = listOf(
    "Aiyl Bank",
    "Optima Bank",
    "DemirBank",
    "KICB",
    "Bakai Bank",
    "MBank",
    "RSK Bank"
)
