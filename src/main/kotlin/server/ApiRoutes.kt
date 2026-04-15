package server

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import models.Employee
import models.FinishedProduct
import models.Ingredient
import models.MeasurementUnit
import models.MODULE_PERMISSION_LABELS
import models.Position
import models.RawMaterial
import services.ValidationException

internal fun AppServer.registerApiRoutes(router: Router) {
    router.get("/api/session").handler { ctx ->
        val sid = readSid(ctx)
        val session = if (sid != null) sessions[sid] else null
        if (session == null) {
            apiJson(ctx, mapOf("authenticated" to false))
        } else {
            apiJson(
                ctx,
                mapOf(
                    "authenticated" to true,
                    "user" to mapOf(
                        "id" to session.userId,
                        "username" to session.username,
                        "roles" to session.roles.sorted(),
                        "canEdit" to session.canEdit,
                        "canDelete" to session.canDelete,
                        "navigation" to navigationItems(session)
                    )
                )
            )
        }
    }

    router.post("/api/login").coroutineHandler { ctx ->
        val body = jsonBody(ctx)
        val username = body.getString("username")?.trim().orEmpty()
        val password = body.getString("password")?.trim().orEmpty()
        val session = authService.tryLogin(username, password)
        if (session == null) {
            apiError(ctx, 401, "Invalid username or password"); return@coroutineHandler
        }
        val sid = java.util.UUID.randomUUID().toString()
        sessions[sid] = session
        setSessionCookie(ctx, sid)
        apiJson(
            ctx,
            mapOf(
                "ok" to true,
                "user" to mapOf(
                    "id" to session.userId,
                    "username" to session.username,
                    "roles" to session.roles.sorted(),
                    "canEdit" to session.canEdit,
                    "canDelete" to session.canDelete,
                    "navigation" to navigationItems(session)
                )
            )
        )
    }

    router.post("/api/logout").handler { ctx ->
        val sid = readSid(ctx)
        if (sid != null) sessions.remove(sid)
        clearSessionCookie(ctx)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/dashboard").coroutineHandler { ctx ->
        val session = requireApiAuth(ctx, null) ?: return@coroutineHandler
        apiJson(
            ctx,
            mapOf(
                "user" to mapOf(
                    "username" to session.username,
                    "roles" to session.roles.sorted(),
                    "canEdit" to session.canEdit,
                    "canDelete" to session.canDelete
                ),
                "summary" to mapOf(
                    "employees" to employees.listAll().size,
                    "rawMaterials" to rawMaterials.listAll().size,
                    "products" to products.listAll().size,
                    "sales" to saleService.listAll().size
                )
            )
        )
    }

    router.get("/api/lookups").coroutineHandler { ctx ->
        requireApiAuth(ctx, null) ?: return@coroutineHandler
        apiJson(
            ctx,
            mapOf(
                "units" to safeLookup { units.listAll() },
                "positions" to safeLookup { positions.listAll() },
                "employees" to safeLookup { employees.listAll().map { mapOf("id" to it.id, "name" to it.fullName) } },
                "rawMaterials" to safeLookup { rawMaterials.listAll().map { mapOf("id" to it.id, "name" to it.name) } },
                "products" to safeLookup { products.listAll().map { mapOf("id" to it.id, "name" to it.name) } },
                "roles" to safeLookup { authRepo.getRoles() }
            )
        )
    }

    router.get("/api/units").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
        apiJson(ctx, units.listAll())
    }
    router.post("/api/units").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_UNITS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = body.getInteger("id") ?: 0
        val name = body.getString("name")?.trim().orEmpty()
        if (name.isBlank()) { apiError(ctx, 400, "Name is required"); return@coroutineHandler }
        if (id == 0) units.create(name) else units.update(MeasurementUnit(id, name))
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/units/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_UNITS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) units.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/positions").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        apiJson(ctx, positions.listAll())
    }
    router.post("/api/positions").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_POSITIONS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = (body.getInteger("id") ?: 0).toShort()
        val title = body.getString("title")?.trim().orEmpty()
        if (title.isBlank()) { apiError(ctx, 400, "Title is required"); return@coroutineHandler }
        if (id == 0.toShort()) positions.create(title) else positions.update(Position(id, title))
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/positions/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_POSITIONS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toShortOrNull() ?: 0
        if (pathId > 0) positions.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/employees").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        apiJson(ctx, employees.listAll())
    }
    router.post("/api/employees").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_EMPLOYEES) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = body.getInteger("id") ?: 0
        val fullName = body.getString("fullName")?.trim().orEmpty()
        val positionId = body.getInteger("positionId")?.toShort()
        val salary = body.getString("salary")?.toBigDecimalOrNull()
        val dateOfBirth = parseDateTimeNullable(body.getString("dateOfBirth"))
        val homeAddress = body.getString("homeAddress")?.trim()
        if (fullName.isBlank() || salary == null) {
            apiError(ctx, 400, "Full name and salary are required"); return@coroutineHandler
        }
        if (id == 0) {
            employees.create(fullName, dateOfBirth, positionId, salary, homeAddress)
        } else {
            employees.update(Employee(id, fullName, dateOfBirth, positionId, salary, homeAddress, null))
        }
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/employees/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_EMPLOYEES) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) employees.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/raw-materials").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
        apiJson(ctx, rawMaterials.listAll())
    }
    router.post("/api/raw-materials").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = body.getInteger("id") ?: 0
        val name = body.getString("name")?.trim().orEmpty()
        val unitId = body.getInteger("unitId")
        if (name.isBlank() || unitId == null) { apiError(ctx, 400, "Name and unit are required"); return@coroutineHandler }
        if (id == 0) {
            rawMaterials.create(name, unitId, 0.0, 0.0)
        } else {
            val existing = rawMaterials.getById(id) ?: run { apiError(ctx, 404, "Raw material not found"); return@coroutineHandler }
            rawMaterials.update(existing.copy(name = name, unitId = unitId))
        }
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/raw-materials/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_RAW_MATERIALS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) rawMaterials.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/products").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales", "Production")) ?: return@coroutineHandler
        apiJson(ctx, products.listAll())
    }
    router.post("/api/products").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.EDIT, MODULE_PRODUCTS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = body.getInteger("id") ?: 0
        val name = body.getString("name")?.trim().orEmpty()
        val unitId = body.getInteger("unitId")
        val unitCostParam = body.getDouble("unitCost")
        val amountParam = body.getDouble("amount")
        if (name.isBlank() || unitId == null || (unitCostParam == null && amountParam == null)) {
            apiError(ctx, 400, "Name, unit, and cost are required"); return@coroutineHandler
        }
        if (id == 0) {
            products.create(name, unitId, 0.0, amountParam ?: 0.0)
        } else {
            val existing = products.getById(id) ?: run { apiError(ctx, 404, "Product not found"); return@coroutineHandler }
            val amount = if (unitCostParam != null) existing.quantity * unitCostParam else amountParam ?: existing.amount
            products.update(existing.copy(name = name, unitId = unitId, amount = amount))
        }
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/products/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales", "Production"), SessionPermission.DELETE, MODULE_PRODUCTS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) products.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/ingredients").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        apiJson(ctx, ingredients.listAll())
    }
    router.post("/api/ingredients").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_INGREDIENTS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = body.getInteger("id") ?: 0
        val productId = body.getInteger("productId")
        val rawMaterialId = body.getInteger("rawMaterialId")
        val quantity = body.getDouble("quantity")
        if (productId == null || rawMaterialId == null || quantity == null) {
            apiError(ctx, 400, "Product, raw material, and quantity are required"); return@coroutineHandler
        }
        if (id == 0) ingredients.create(productId, rawMaterialId, quantity)
        else ingredients.update(Ingredient(id, productId, rawMaterialId, quantity, null, null))
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/ingredients/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_INGREDIENTS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) ingredients.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/purchases").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing")) ?: return@coroutineHandler
        apiJson(ctx, purchaseService.listAll())
    }
    router.post("/api/purchases").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.EDIT, MODULE_PURCHASE) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val rawMaterialId = body.getInteger("rawMaterialId")
        val employeeId = body.getInteger("employeeId")
        val quantity = body.getDouble("quantity")
        val unitPrice = body.getDouble("unitPrice")
        val purchaseDate = parseDateTime(body.getString("purchaseDate"))
        if (rawMaterialId == null || employeeId == null || quantity == null || unitPrice == null) {
            apiError(ctx, 400, "Raw material, employee, quantity, and unit price are required"); return@coroutineHandler
        }
        purchaseService.create(rawMaterialId, quantity, unitPrice, purchaseDate, employeeId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/purchases/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) purchaseService.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.post("/api/purchases/rollback").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Purchasing"), SessionPermission.DELETE, MODULE_PURCHASE) ?: return@coroutineHandler
        purchaseService.rollback()
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/production").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        apiJson(ctx, production.listAll())
    }
    router.post("/api/production").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val productId = body.getInteger("productId")
        val employeeId = body.getInteger("employeeId")
        val quantity = body.getDouble("quantity")
        val productionDate = parseDateTime(body.getString("productionDate"))
        if (productId == null || employeeId == null || quantity == null) {
            apiError(ctx, 400, "Product, employee, and quantity are required"); return@coroutineHandler
        }
        production.create(productId, quantity, productionDate, employeeId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/production/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) production.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.post("/api/production/rollback").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION) ?: return@coroutineHandler
        production.deleteLast()
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/sales").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
        apiJson(ctx, saleService.listAll())
    }
    router.post("/api/sales").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales"), SessionPermission.EDIT, MODULE_SALES) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val productId = body.getInteger("productId")
        val employeeId = body.getInteger("employeeId")
        val quantity = body.getDouble("quantity")
        val saleDate = parseDateTime(body.getString("saleDate"))
        if (productId == null || employeeId == null || quantity == null) {
            apiError(ctx, 400, "Product, employee, and quantity are required"); return@coroutineHandler
        }
        saleService.create(productId, quantity, saleDate, employeeId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/sales/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) saleService.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.post("/api/sales/rollback").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales"), SessionPermission.DELETE, MODULE_SALES) ?: return@coroutineHandler
        saleService.rollback()
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/salaries").coroutineHandler { ctx ->
        requireApiAuth(ctx, null) ?: return@coroutineHandler
        apiJson(ctx, salaries.listAll())
    }
    router.post("/api/salaries").coroutineHandler { ctx ->
        requireApiAuth(ctx, null, SessionPermission.EDIT, MODULE_SALARY) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val employeeId = body.getInteger("employeeId")
        if (employeeId == null) { apiError(ctx, 400, "Employee is required"); return@coroutineHandler }
        val amountParam = body.getDouble("amount")
        val paymentDate = parseDateTime(body.getString("paymentDate"))
        val noteRaw = body.getString("note")?.trim()
        val note = if (noteRaw.isNullOrBlank()) null else noteRaw
        salaries.create(employeeId, amountParam, paymentDate, note)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/salaries/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) salaries.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }
    router.post("/api/salaries/rollback").coroutineHandler { ctx ->
        requireApiAuth(ctx, null, SessionPermission.DELETE, MODULE_SALARY) ?: return@coroutineHandler
        salaries.deleteLast()
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/production-requests").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production")) ?: return@coroutineHandler
        apiJson(ctx, productionRequestService.listAll())
    }
    router.post("/api/production-requests").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.EDIT, MODULE_PRODUCTION_REQUESTS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val applicantName = body.getString("applicantName")?.trim().orEmpty()
        val productId = body.getInteger("productId")
        val quantity = body.getDouble("quantity")
        if (applicantName.isBlank() || productId == null || quantity == null) {
            apiError(ctx, 400, "Applicant name, product and quantity are required"); return@coroutineHandler
        }
        try {
            val id = productionRequestService.submit(applicantName, productId, quantity)
            apiJson(ctx, mapOf("ok" to true, "id" to id))
        } catch (e: ValidationException) {
            apiError(ctx, 400, e.message ?: "Validation error")
        }
    }
    router.delete("/api/production-requests/:id").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Production"), SessionPermission.DELETE, MODULE_PRODUCTION_REQUESTS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId > 0) productionRequestService.delete(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/reports").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin", "Sales")) ?: return@coroutineHandler
        apiJson(
            ctx,
            mapOf(
                "rawInventory" to rawInventory.listAll(),
                "productInventory" to productInventory.listAll(),
                "sales" to salesExtended.listAll()
            )
        )
    }

    router.get("/api/budget").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        apiJson(ctx, budgets.listAll())
    }
    router.post("/api/budget").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_BUDGET) ?: return@coroutineHandler
        val amount = jsonBody(ctx).getDouble("amount")
        if (amount == null) { apiError(ctx, 400, "Amount is required"); return@coroutineHandler }
        budgets.create(amount)
        apiJson(ctx, mapOf("ok" to true))
    }

    router.get("/api/users").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin")) ?: return@coroutineHandler
        apiJson(
            ctx,
            mapOf(
                "users" to authRepo.getUsersWithRoles(),
                "roles" to authRepo.getRoles(),
                "employees" to employees.listAll().map { mapOf("id" to it.id, "name" to it.fullName) }
            )
        )
    }
    router.post("/api/users").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val id = body.getInteger("id") ?: 0
        val username = body.getString("username")?.trim().orEmpty()
        val password = body.getString("password")?.trim().orEmpty()
        val isActive = body.getBoolean("isActive") ?: false
        val employeeId = body.getInteger("employeeId")
        val roles = authRepo.getRoles()
        val selectedRoleIds = body.getJsonArray("roleIds")?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        if (username.isBlank()) { apiError(ctx, 400, "Username is required"); return@coroutineHandler }
        if (id == 0) {
            if (password.isBlank()) { apiError(ctx, 400, "Password is required for new user"); return@coroutineHandler }
            val (hash, salt) = authService.createPasswordHash(password)
            authRepo.saveUserWithRoles(0, username, isActive, hash, salt, selectedRoleIds.filter { rid -> roles.any { it.id == rid } }, employeeId)
        } else {
            val hashAndSalt = if (password.isBlank()) null else authService.createPasswordHash(password)
            authRepo.saveUserWithRoles(id, username, isActive, hashAndSalt?.first, hashAndSalt?.second, selectedRoleIds.filter { rid -> roles.any { it.id == rid } }, employeeId)
        }
        apiJson(ctx, mapOf("ok" to true))
    }
    router.post("/api/roles/permissions").coroutineHandler { ctx ->
        requireApiAuth(ctx, setOf("Admin"), SessionPermission.EDIT, MODULE_USERS) ?: return@coroutineHandler
        val body = jsonBody(ctx)
        val roleId = body.getInteger("roleId") ?: 0
        if (roleId <= 0) { apiError(ctx, 400, "Role is required"); return@coroutineHandler }
        val permissions = body.getJsonArray("permissions") ?: JsonArray()
        if (permissions.isEmpty) {
            MODULE_PERMISSION_LABELS.keys.forEach { moduleKey ->
                authRepo.saveRolePermission(roleId, moduleKey, false, false)
            }
        } else {
            permissions.forEach { item ->
                val json = item as? JsonObject ?: return@forEach
                val moduleKey = json.getString("moduleKey")?.trim().orEmpty()
                if (!MODULE_PERMISSION_LABELS.containsKey(moduleKey)) return@forEach
                authRepo.saveRolePermission(
                    roleId = roleId,
                    moduleKey = moduleKey,
                    canEdit = json.getBoolean("canEdit") ?: false,
                    canDelete = json.getBoolean("canDelete") ?: false
                )
            }
        }
        apiJson(ctx, mapOf("ok" to true))
    }
    router.delete("/api/users/:id").coroutineHandler { ctx ->
        val session = requireApiAuth(ctx, setOf("Admin"), SessionPermission.DELETE, MODULE_USERS) ?: return@coroutineHandler
        val pathId = ctx.pathParam("id")?.toIntOrNull() ?: 0
        if (pathId == session.userId) {
            apiError(ctx, 400, "Cannot delete the account you are currently logged in as.")
            return@coroutineHandler
        }
        if (pathId > 0) authRepo.deleteUser(pathId)
        apiJson(ctx, mapOf("ok" to true))
    }
}
