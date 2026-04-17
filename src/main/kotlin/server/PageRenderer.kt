package server

import models.*
import models.RequestStatus
import java.time.format.DateTimeFormatter

internal fun AppServer.loginPage(error: String?): String {
    val errorBlock = if (error.isNullOrBlank()) "" else "<div class=\"alert alert-error\">${html(error)}</div>"
    return templates.render("login.html", mapOf("error" to errorBlock))
}

internal suspend fun AppServer.layoutPage(title: String, session: AuthSession, content: String): String {
    val budget = budgets.listAll().firstOrNull()?.budgetAmount
    val budgetLabel = budget?.let { format2(it) } ?: "-"
    val creditFunds = creditService.listAll().filter { it.isActive }.sumOf { it.remainingAmount }
    val creditLabel = if (creditFunds > 0.0) {
        "<span class=\"budget-credit-note\">Credit (${format2(creditFunds)})</span>"
    } else ""
    return templates.render(
        "layout.html",
        mapOf(
            "title" to html(title),
            "nav" to buildNav(session),
            "username" to html(session.username),
            "roles" to html(session.roles.sorted().joinToString(", ")),
            "budget" to budgetLabel,
            "budgetCredit" to creditLabel,
            "content" to content
        )
    )
}

internal fun AppServer.simplePage(title: String, content: String): String = """
    <html>
    <head><title>${html(title)}</title></head>
    <body>
        $content
    </body>
    </html>
""".trimIndent()

internal suspend fun AppServer.dashboardPage(session: AuthSession): String {
    val navGroups = navigationItems(session)
        .groupBy { it.getValue("group") }
        .entries
        .joinToString("\n") { (group, items) ->
            val links = items.joinToString("\n") { item ->
                "<li><a href=\"${item.getValue("to")}\">${html(item.getValue("label"))}</a></li>"
            }
            """
            <section class="section-card dashboard-section">
                <h2>${html(group)}</h2>
                <ul class="dashboard-link-list">
                    $links
                </ul>
            </section>
            """.trimIndent()
        }
    val content = """
        <h1>Dashboard</h1>
        <div class="dashboard-sections">$navGroups</div>
    """.trimIndent()
    return layoutPage("Dashboard", session, content)
}

internal suspend fun AppServer.unitsPage(session: AuthSession, list: List<MeasurementUnit>, edit: MeasurementUnit?): String {
    val rows = list.joinToString("\n") { u ->
        "<tr><td>${u.id}</td><td>${html(u.name)}</td><td>${rowActions(session, MODULE_UNITS, "/units?editId=${u.id}", "/units/delete", u.id)}</td></tr>"
    }
    val formSection = editFormOrNotice(
        session, MODULE_UNITS,
        """
        <section class="section-card compact-card">
            <h2>Measurement unit</h2>
            <form method="post" action="/units/save" class="managed-form compact-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Name</label>
                    <input name="name" value="${html(edit?.name ?: "")}" />
                </div>
                <div class="form-actions">
                    <button type="submit">Save unit</button>
                    ${cancelEditLink("/units", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing measurement units is not allowed for your roles."
    )
    val content = templates.render("pages/units.html", mapOf("formSection" to formSection, "rows" to rows))
    return layoutPage("Units", session, content)
}

internal suspend fun AppServer.positionsPage(session: AuthSession, list: List<Position>, edit: Position?): String {
    val rows = list.joinToString("\n") { p ->
        "<tr><td>${p.id}</td><td>${html(p.title)}</td><td>${rowActions(session, MODULE_POSITIONS, "/positions?editId=${p.id}", "/positions/delete", p.id)}</td></tr>"
    }
    val formSection = editFormOrNotice(
        session, MODULE_POSITIONS,
        """
        <section class="section-card compact-card">
            <h2>Position</h2>
            <form method="post" action="/positions/save" class="managed-form compact-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Title</label>
                    <input name="title" value="${html(edit?.title ?: "")}" />
                </div>
                <div class="form-actions">
                    <button type="submit">Save position</button>
                    ${cancelEditLink("/positions", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing positions is not allowed for your roles."
    )
    val content = templates.render("pages/positions.html", mapOf("formSection" to formSection, "rows" to rows))
    return layoutPage("Positions", session, content)
}

internal suspend fun AppServer.employeesPage(
    session: AuthSession,
    list: List<Employee>,
    positionsList: List<Position>,
    edit: Employee?
): String {
    val rows = list.joinToString("\n") { e ->
        "<tr><td>${e.id}</td><td>${html(e.fullName)}</td><td>${e.positionTitle ?: e.positionId}</td>" +
            "<td>${e.dateOfBirth ?: ""}</td><td>${e.salary}</td><td>${html(e.homeAddress ?: "")}</td><td>" +
            "${rowActions(session, MODULE_EMPLOYEES, "/employees?editId=${e.id}", "/employees/delete", e.id)}</td></tr>"
    }
    val positionOptions = positionsList.joinToString("\n") { p ->
        val selected = if (edit?.positionId == p.id) "selected" else ""
        "<option value=\"${p.id}\" $selected>${html(p.title)}</option>"
    }
    val formSection = editFormOrNotice(
        session, MODULE_EMPLOYEES,
        """
        <section class="section-card">
            <h2>Employee card</h2>
            <form method="post" action="/employees/save" class="managed-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Full name</label>
                    <input name="fullName" value="${html(edit?.fullName ?: "")}" />
                </div>
                <div>
                    <label>Position</label>
                    <select name="positionId">$positionOptions</select>
                </div>
                <div>
                    <label>Date of birth</label>
                    <input type="date" name="dateOfBirth" value="${edit?.dateOfBirth?.toLocalDate()?.toString() ?: ""}" />
                </div>
                <div>
                    <label>Salary</label>
                    <input name="salary" value="${edit?.salary?.toPlainString() ?: ""}" />
                </div>
                <div>
                    <label>Home address</label>
                    <input name="homeAddress" value="${html(edit?.homeAddress ?: "")}" />
                </div>
                <div class="form-actions">
                    <button type="submit">Save employee</button>
                    ${cancelEditLink("/employees", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing employees is not allowed for your roles."
    )
    val content = templates.render("pages/employees.html", mapOf("formSection" to formSection, "rows" to rows))
    return layoutPage("Employees", session, content)
}

internal suspend fun AppServer.salaryPage(
    session: AuthSession,
    list: List<SalaryPayment>,
    employeesList: List<Employee>
): String {
    val rows = list.joinToString("\n") { s ->
        "<tr><td>${s.id}</td><td>${html(s.employeeName ?: "")}</td>" +
            "<td>${s.amount}</td><td>${s.paymentDate}</td><td>${html(s.note ?: "")}</td><td>" +
            "${rowActions(session, MODULE_SALARY, deleteAction = "/salary/delete", id = s.id)}</td></tr>"
    }
    val employeeOptions = employeesList.joinToString("\n") { e ->
        "<option value=\"${e.id}\">${html(e.fullName)}</option>"
    }
    val formSection = if (canEdit(session, MODULE_SALARY) || canDelete(session, MODULE_SALARY)) {
        buildString {
            if (canEdit(session, MODULE_SALARY)) {
                append("""
                    <section class="section-card">
                        <h2>Salary payment</h2>
                        <form method="post" action="/salary/save" class="managed-form">
                            <div>
                                <label>Employee</label>
                                <select name="employeeId">$employeeOptions</select>
                            </div>
                            <div>
                                <label>Amount</label>
                                <input name="amount" placeholder="Leave empty to use employee salary" />
                            </div>
                            <div>
                                <label>Payment date</label>
                                <input type="date" name="paymentDate" />
                            </div>
                            <div>
                                <label>Note</label>
                                <input name="note" />
                            </div>
                            <button type="submit">Post salary payment</button>
                """.trimIndent())
            } else {
                append(permissionNotice("Creating salary payments is not allowed for your roles."))
            }
            if (canDelete(session, MODULE_SALARY)) {
                append("""
                        <form method="post" action="/salary/rollback" class="secondary-actions">
                            <button type="submit">Rollback last payment</button>
                        </form>
                    </section>
                """.trimIndent())
            } else if (canEdit(session, MODULE_SALARY)) {
                append("</section>")
            }
        }
    } else {
        permissionNotice("Changing salary records is not allowed for your roles.")
    }
    val content = templates.render("pages/salary.html", mapOf("formSection" to formSection, "rows" to rows))
    return layoutPage("Salaries", session, content)
}

internal suspend fun AppServer.rawMaterialsPage(
    session: AuthSession,
    list: List<RawMaterial>,
    unitsList: List<MeasurementUnit>,
    edit: RawMaterial?
): String {
    val rows = list.joinToString("\n") { r ->
        "<tr><td>${r.id}</td><td>${html(r.name)}</td><td>${r.unitName ?: r.unitId}</td>" +
            "<td>${format3(r.quantity)}</td><td>${format2(r.amount)}</td><td>" +
            "${rowActions(session, MODULE_RAW_MATERIALS, "/raw-materials?editId=${r.id}", "/raw-materials/delete", r.id)}</td></tr>"
    }
    val unitOptions = unitsList.joinToString("\n") { u ->
        val selected = if (edit?.unitId == u.id) "selected" else ""
        "<option value=\"${u.id}\" $selected>${html(u.name)}</option>"
    }
    val formSection = editFormOrNotice(
        session, MODULE_RAW_MATERIALS,
        """
        <section class="section-card">
            <h2>Material card</h2>
            <form method="post" action="/raw-materials/save" class="managed-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Name</label>
                    <input name="name" value="${html(edit?.name ?: "")}" />
                </div>
                <div>
                    <label>Unit</label>
                    <select name="unitId">$unitOptions</select>
                </div>
                <div>
                    <label>Quantity in stock</label>
                    <input name="quantity" value="${format3(edit?.quantity ?: 0.0)}" readonly />
                    <small class="field-hint">Readonly. Changes only through purchase documents.</small>
                </div>
                <div>
                    <label>Total amount</label>
                    <input name="amount" value="${format2(edit?.amount ?: 0.0)}" readonly />
                    <small class="field-hint">Readonly. Recalculated from purchase operations.</small>
                </div>
                <div class="form-actions">
                    <button type="submit">Save material</button>
                    ${cancelEditLink("/raw-materials", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing raw materials is not allowed for your roles."
    )
    val content = templates.render("pages/raw-materials.html", mapOf("formSection" to formSection, "rows" to rows))
    return layoutPage("Raw materials", session, content)
}

internal suspend fun AppServer.productsPage(
    session: AuthSession,
    list: List<FinishedProduct>,
    unitsList: List<MeasurementUnit>,
    edit: FinishedProduct?
): String {
    val rows = list.joinToString("\n") { p ->
        "<tr><td>${p.id}</td><td>${html(p.name)}</td><td>${p.unitName ?: p.unitId}</td>" +
            "<td>${format3(p.quantity)}</td><td>${format2(unitCost(p.amount, p.quantity))}</td><td>" +
            "${rowActions(session, MODULE_PRODUCTS, "/products?editId=${p.id}", "/products/delete", p.id)}</td></tr>"
    }
    val unitOptions = unitsList.joinToString("\n") { u ->
        val selected = if (edit?.unitId == u.id) "selected" else ""
        "<option value=\"${u.id}\" $selected>${html(u.name)}</option>"
    }
    val formSection = editFormOrNotice(
        session, MODULE_PRODUCTS,
        """
        <section class="section-card">
            <h2>Product card</h2>
            <form method="post" action="/products/save" class="managed-form" id="products-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Name</label>
                    <input name="name" value="${html(edit?.name ?: "")}" />
                </div>
                <div>
                    <label>Unit</label>
                    <select name="unitId">$unitOptions</select>
                </div>
                <div>
                    <label>Quantity in stock</label>
                    <input id="product-quantity" name="quantity" value="${format3(edit?.quantity ?: 0.0)}" readonly />
                    <small class="field-hint">Readonly. Updated by production and sales.</small>
                </div>
                <div>
                    <label>Cost</label>
                    <input id="product-unit-cost" name="unitCost" value="${format2(unitCost(edit?.amount ?: 0.0, edit?.quantity ?: 0.0))}" />
                    <small class="field-hint">Enter the product cost price. Sale price is suggested automatically.</small>
                </div>
                <div>
                    <label>Sale price (+15%)</label>
                    <input id="product-sale-price" value="${format2(calculateSaleUnitPrice(edit?.amount ?: 0.0, edit?.quantity ?: 0.0))}" readonly />
                    <small class="field-hint">Reference only. Used as a visual recommendation.</small>
                </div>
                <input id="product-amount" name="amount" type="hidden" value="${format2(edit?.amount ?: 0.0)}" />
                <div class="form-actions">
                    <button type="submit">Save product</button>
                    ${cancelEditLink("/products", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing products is not allowed for your roles."
    )
    val content = templates.render("pages/products.html", mapOf("formSection" to formSection, "rows" to rows))
    return layoutPage("Products", session, content)
}

internal suspend fun AppServer.ingredientsPage(
    session: AuthSession,
    list: List<Ingredient>,
    productsList: List<FinishedProduct>,
    rawList: List<RawMaterial>,
    edit: Ingredient?
): String {
    val groupedIngredients = if (list.isEmpty()) {
        """
        <section class="section-card ingredient-group-card">
            <div class="empty-state">No ingredients added yet.</div>
        </section>
        """.trimIndent()
    } else {
        list.groupBy { it.productName ?: it.productId.toString() }
            .entries
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { (productName, ingredients) ->
                val ingredientItems = ingredients
                    .sortedBy { (it.rawMaterialName ?: it.rawMaterialId.toString()).lowercase() }
                    .joinToString("\n") { ingredient ->
                        val editingClass = if (edit?.id == ingredient.id) " is-editing-ingredient" else ""
                        """
                        <div class="ingredient-item$editingClass">
                            <div class="ingredient-item-main">
                                <div class="ingredient-raw-name">${html(ingredient.rawMaterialName ?: ingredient.rawMaterialId.toString())}</div>
                                <div class="ingredient-meta">
                                    <span>ID ${ingredient.id}</span>
                                    <span>${format3(ingredient.quantity)} per unit</span>
                                </div>
                            </div>
                            <div class="ingredient-item-actions">
                                ${rowActions(session, MODULE_INGREDIENTS, "/ingredients?editId=${ingredient.id}", "/ingredients/delete", ingredient.id)}
                            </div>
                        </div>
                        """.trimIndent()
                    }
                """
                <section class="section-card ingredient-group-card">
                    <div class="ingredient-group-head">
                        <h3>${html(productName)}</h3>
                        <span class="ingredient-count">${ingredients.size} item${if (ingredients.size == 1) "" else "s"}</span>
                    </div>
                    <div class="ingredient-group-list">
                        $ingredientItems
                    </div>
                </section>
                """.trimIndent()
            }
    }
    val tableRows = if (list.isEmpty()) {
        "<tr><td colspan=\"5\" class=\"empty-state-cell\">No ingredients added yet.</td></tr>"
    } else {
        list.joinToString("\n") { i ->
            val editingClass = if (edit?.id == i.id) " class=\"is-editing-row\"" else ""
            "<tr$editingClass><td>${i.id}</td><td>${html(i.productName ?: i.productId.toString())}</td><td>${html(i.rawMaterialName ?: i.rawMaterialId.toString())}</td>" +
                "<td>${format3(i.quantity)}</td><td>${rowActions(session, MODULE_INGREDIENTS, "/ingredients?editId=${i.id}", "/ingredients/delete", i.id)}</td></tr>"
        }
    }
    val productOptions = productsList.joinToString("\n") { p ->
        val selected = if (edit?.productId == p.id) "selected" else ""
        "<option value=\"${p.id}\" $selected>${html(p.name)}</option>"
    }
    val rawOptions = rawList.joinToString("\n") { r ->
        val selected = if (edit?.rawMaterialId == r.id) "selected" else ""
        "<option value=\"${r.id}\" $selected>${html(r.name)}</option>"
    }
    val formTitle = if (edit == null) "Add ingredient" else "Edit ingredient #${edit.id}"
    val formActionLabel = if (edit == null) "Save ingredient" else "Update ingredient"
    val formSection = editFormOrNotice(
        session, MODULE_INGREDIENTS,
        """
        <section class="section-card">
            <h2>$formTitle</h2>
            <form method="post" action="/ingredients/save" class="managed-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Product</label>
                    <select name="productId">$productOptions</select>
                    <small class="field-hint">Choose the finished product whose recipe you are editing.</small>
                </div>
                <div>
                    <label>Raw material</label>
                    <select name="rawMaterialId">$rawOptions</select>
                    <small class="field-hint">Choose the material that will be consumed in this recipe.</small>
                </div>
                <div>
                    <label>Quantity per unit</label>
                    <input name="quantity" type="number" step="0.001" min="0" value="${edit?.quantity?.toString() ?: ""}" />
                    <small class="field-hint">How much material is needed to produce one unit of product.</small>
                </div>
                <div class="form-actions">
                    <button type="submit">$formActionLabel</button>
                    ${cancelEditLink("/ingredients", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing ingredients is not allowed for your roles."
    )
    val content = templates.render(
        "pages/ingredients.html",
        mapOf("formSection" to formSection, "rows" to tableRows, "groupedIngredients" to groupedIngredients)
    )
    return layoutPage("Ingredients", session, content)
}

internal suspend fun AppServer.purchasePage(
    session: AuthSession,
    list: List<PurchaseRawMaterial>,
    rawList: List<RawMaterial>,
    employeesList: List<Employee>,
    message: String?
): String {
    val rawOptions = rawList.joinToString("\n") { r -> "<option value=\"${r.id}\">${html(r.name)}</option>" }
    val employeeOptions = employeesList.joinToString("\n") { e -> "<option value=\"${e.id}\">${html(e.fullName)}</option>" }
    val rows = list.joinToString("\n") { p ->
        val statusBadge = "<span class=\"record-status is-archived\">Archived</span>"
        "<tr><td>${p.purchaseDate.toLocalDate()}</td><td>${p.rawMaterialName ?: p.rawMaterialId}</td>" +
            "<td>${format3(p.quantity)}</td><td>${format2(p.amount)}</td><td>${p.employeeName ?: p.employeeId}</td><td>$statusBadge</td><td>" +
            "${rowActions(session, MODULE_PURCHASE, deleteAction = "/purchase/delete", id = p.id)}</td></tr>"
    }
    val messageBlock = if (message.isNullOrBlank()) "" else "<div class=\"alert alert-info\">${html(message)}</div>"
    val formSection = buildString {
        if (canEdit(session, MODULE_PURCHASE)) {
            append("""
                <section class="section-card">
                    <h2>New purchase</h2>
                    <form method="post" action="/purchase/save" class="managed-form" id="purchase-form">
                        <div>
                            <label>Raw material</label>
                            <select name="rawMaterialId">$rawOptions</select>
                        </div>
                        <div>
                            <label>Employee</label>
                            <select name="employeeId">$employeeOptions</select>
                        </div>
                        <div>
                            <label>Date</label>
                            <input type="date" name="purchaseDate" />
                        </div>
                        <div>
                            <label>Quantity</label>
                            <input id="purchase-quantity" name="quantity" />
                        </div>
                        <div>
                            <label>Unit price</label>
                            <input id="purchase-unit-price" name="unitPrice" />
                        </div>
                        <div>
                            <label>Total amount</label>
                            <input id="purchase-amount" name="amount" readonly />
                            <small class="field-hint">Calculated automatically from quantity and unit price.</small>
                        </div>
                        <button type="submit">Post purchase</button>
                    </form>
            """.trimIndent())
            if (canDelete(session, MODULE_PURCHASE)) {
                append("""
                        <form method="post" action="/purchase/rollback" class="secondary-actions">
                            <button type="submit">Rollback last purchase</button>
                        </form>
                    </section>
                """.trimIndent())
            } else {
                append("</section>")
            }
        } else if (canDelete(session, MODULE_PURCHASE)) {
            append(permissionNotice("Creating purchases is not allowed for your roles."))
            append("""
                <section class="section-card">
                    <form method="post" action="/purchase/rollback" class="secondary-actions">
                        <button type="submit">Rollback last purchase</button>
                    </form>
                </section>
            """.trimIndent())
        } else {
            append(permissionNotice("Changing purchase records is not allowed for your roles."))
        }
    }
    val content = templates.render(
        "pages/purchase.html",
        mapOf("formSection" to formSection, "rows" to rows, "message" to messageBlock)
    )
    return layoutPage("Purchase", session, content)
}

internal suspend fun AppServer.productionPage(
    session: AuthSession,
    list: List<ProductProduction>,
    productsList: List<FinishedProduct>,
    employeesList: List<Employee>
): String {
    val productOptions = productsList.joinToString("\n") { p -> "<option value=\"${p.id}\">${html(p.name)}</option>" }
    val employeeOptions = employeesList.joinToString("\n") { e -> "<option value=\"${e.id}\">${html(e.fullName)}</option>" }
    val rows = list.joinToString("\n") { p ->
        "<tr><td>${p.productionDate.toLocalDate()}</td><td>${p.productName ?: p.productId}</td>" +
            "<td>${p.quantity}</td><td>${p.employeeName ?: p.employeeId}</td><td>" +
            "${rowActions(session, MODULE_PRODUCTION, deleteAction = "/production/delete", id = p.id)}</td></tr>"
    }
    val formSection = buildString {
        if (canEdit(session, MODULE_PRODUCTION)) {
            append("""
                <section class="section-card">
                    <h2>Production entry</h2>
                    <form method="post" action="/production/save" class="managed-form">
                        <div>
                            <label>Product</label>
                            <select name="productId">$productOptions</select>
                        </div>
                        <div>
                            <label>Employee</label>
                            <select name="employeeId">$employeeOptions</select>
                        </div>
                        <div>
                            <label>Date</label>
                            <input type="date" name="productionDate" />
                        </div>
                        <div>
                            <label>Quantity</label>
                            <input name="quantity" />
                        </div>
                        <button type="submit">Post production</button>
                    </form>
            """.trimIndent())
            if (canDelete(session, MODULE_PRODUCTION)) {
                append("""
                        <form method="post" action="/production/rollback" class="secondary-actions">
                            <button type="submit">Rollback last production</button>
                        </form>
                    </section>
                """.trimIndent())
            } else {
                append("</section>")
            }
        } else if (canDelete(session, MODULE_PRODUCTION)) {
            append(permissionNotice("Creating production records is not allowed for your roles."))
            append("""
                <section class="section-card">
                    <form method="post" action="/production/rollback" class="secondary-actions">
                        <button type="submit">Rollback last production</button>
                    </form>
                </section>
            """.trimIndent())
        } else {
            append(permissionNotice("Changing production records is not allowed for your roles."))
        }
    }
    val content = templates.render(
        "pages/production.html",
        mapOf("formSection" to formSection, "rows" to rows, "message" to "")
    )
    return layoutPage("Production", session, content)
}

internal suspend fun AppServer.salesPage(
    session: AuthSession,
    list: List<ProductSale>,
    productsList: List<FinishedProduct>,
    employeesList: List<Employee>
): String {
    val productOptions = productsList.joinToString("\n") { p ->
        "<option value=\"${p.id}\" data-available=\"${format3(p.quantity)}\" " +
            "data-unit-cost=\"${format2(unitCost(p.amount, p.quantity))}\" " +
            "data-sale-price=\"${format2(calculateSaleUnitPrice(p.amount, p.quantity))}\">${html(p.name)}</option>"
    }
    val employeeOptions = employeesList.joinToString("\n") { e -> "<option value=\"${e.id}\">${html(e.fullName)}</option>" }
    val rows = list.joinToString("\n") { s ->
        "<tr><td>${s.saleDate.toLocalDate()}</td><td>${s.productName ?: s.productId}</td>" +
            "<td>${format3(s.quantity)}</td><td>${format2(s.amount)}</td><td>${s.employeeName ?: s.employeeId}</td><td>" +
            "${rowActions(session, MODULE_SALES, deleteAction = "/sales/delete", id = s.id)}</td></tr>"
    }
    val formSection = buildString {
        if (canEdit(session, MODULE_SALES)) {
            append("""
                <section class="section-card">
                    <h2>New sale</h2>
                    <form method="post" action="/sales/save" class="managed-form" id="sales-form">
                        <div>
                            <label>Product</label>
                            <select id="sales-product" name="productId">$productOptions</select>
                        </div>
                        <div>
                            <label>Employee</label>
                            <select name="employeeId">$employeeOptions</select>
                        </div>
                        <div>
                            <label>Date</label>
                            <input type="date" name="saleDate" />
                        </div>
                        <div>
                            <label>Available in stock</label>
                            <input id="sales-available" readonly />
                        </div>
                        <div>
                            <label>Quantity</label>
                            <input id="sales-quantity" name="quantity" />
                        </div>
                        <div>
                            <label>Cost per unit</label>
                            <input id="sales-unit-cost" readonly />
                        </div>
                        <div>
                            <label>Sale price (+15%)</label>
                            <input id="sales-unit-price" readonly />
                        </div>
                        <div>
                            <label>Total amount</label>
                            <input id="sales-amount" name="amount" readonly />
                            <small class="field-hint">Calculated automatically.</small>
                        </div>
                        <button type="submit">Post sale</button>
                    </form>
            """.trimIndent())
            if (canDelete(session, MODULE_SALES)) {
                append("""
                        <form method="post" action="/sales/rollback" class="secondary-actions">
                            <button type="submit">Rollback last sale</button>
                        </form>
                    </section>
                """.trimIndent())
            } else {
                append("</section>")
            }
        } else if (canDelete(session, MODULE_SALES)) {
            append(permissionNotice("Creating sales is not allowed for your roles."))
            append("""
                <section class="section-card">
                    <form method="post" action="/sales/rollback" class="secondary-actions">
                        <button type="submit">Rollback last sale</button>
                    </form>
                </section>
            """.trimIndent())
        } else {
            append(permissionNotice("Changing sales records is not allowed for your roles."))
        }
    }
    val content = templates.render(
        "pages/sales.html",
        mapOf("formSection" to formSection, "rows" to rows, "message" to "")
    )
    return layoutPage("Sales", session, content)
}

internal suspend fun AppServer.reportsPage(
    session: AuthSession,
    raw: List<RawMaterialInventoryView>,
    prodList: List<ProductInventoryView>,
    sales: List<SalesExtendedView>
): String {
    val rawRows = raw.joinToString("\n") { r ->
        val uc = unitCost(r.currentAmount, r.currentQuantity)
        "<tr><td>${html(r.rawMaterialName)}</td><td>${r.unitName}</td><td>${r.currentQuantity}</td>" +
            "<td>${format2(uc)}</td><td>${format2(r.currentAmount)}</td></tr>"
    }
    val productRows = prodList.joinToString("\n") { p ->
        val uc = unitCost(p.currentAmount, p.currentQuantity)
        "<tr><td>${html(p.productName)}</td><td>${p.unitName}</td><td>${p.currentQuantity}</td>" +
            "<td>${format2(uc)}</td><td>${format2(p.currentAmount)}</td></tr>"
    }
    val salesRows = sales.joinToString("\n") { s ->
        val price = s.pricePerUnit ?: unitCost(s.amount, s.quantity)
        "<tr><td>${s.saleDate.toLocalDate()}</td><td>${html(s.productName)}</td>" +
            "<td>${s.quantity}</td><td>${format2(price)}</td><td>${format2(s.amount)}</td>" +
            "<td>${html(s.employeeName)}</td></tr>"
    }
    val content = templates.render(
        "pages/reports.html",
        mapOf("rawRows" to rawRows, "productRows" to productRows, "salesRows" to salesRows)
    )
    return layoutPage("Reports", session, content)
}

internal suspend fun AppServer.budgetPage(session: AuthSession, list: List<Budget>): String {
    val creditList = credits.listAll()
    val rows = list.joinToString("\n") { b ->
        "<tr><td>${b.id}</td><td>${format2(b.budgetAmount)}</td></tr>"
    }
    val creditTypeLabel = mapOf(
        "SALARY" to "Salary credit",
        "PRODUCTION" to "Production credit"
    )
    val creditRows = creditList.joinToString("\n") { credit ->
        val statusClass = if (credit.isActive) "is-active" else "is-closed"
        val statusText = if (credit.isActive) "Active" else "Closed"
        val status = "<span class=\"credit-status $statusClass\">$statusText</span>"
        val typeLabel = creditTypeLabel[credit.creditType] ?: credit.creditType
        val typeClass = if (credit.creditType == "SALARY") "type-salary" else "type-production"
        val typeBadge = "<span class=\"type-badge $typeClass\">$typeLabel</span>"

        val actionsHtml = buildString {
            if (canEdit(session, MODULE_BUDGET) && credit.isActive) {
                append("<form method=\"post\" action=\"/credits/pay-monthly\" class=\"credit-action-form credit-monthly-form\">")
                append("<input type=\"hidden\" name=\"id\" value=\"${credit.id}\"/>")
                append("<button type=\"submit\" class=\"btn-small btn-monthly\" title=\"Auto-pay ${format2(credit.monthlyPayment)}\">")
                append("<i class=\"fa-solid fa-calendar-check\"></i> Monthly")
                append("</button></form>")
            }
            if (canEdit(session, MODULE_BUDGET) && credit.isActive) {
                append("<form method=\"post\" action=\"/credits/pay\" class=\"credit-action-form credit-custom-form\">")
                append("<input type=\"hidden\" name=\"id\" value=\"${credit.id}\"/>")
                append("<input type=\"number\" step=\"0.01\" name=\"paymentAmount\" placeholder=\"Amount\" class=\"input-small\"/>")
                append("<button type=\"submit\" class=\"btn-small btn-pay\">Pay</button>")
                append("</form>")
            }
            if (canDelete(session, MODULE_BUDGET)) {
                append("<form method=\"post\" action=\"/credits/delete\" class=\"credit-action-form\">")
                append("<input type=\"hidden\" name=\"id\" value=\"${credit.id}\"/>")
                append("<button type=\"submit\" class=\"btn-small btn-danger\">Delete</button>")
                append("</form>")
            }
        }

        "<tr>" +
            "<td data-label=\"Type\">$typeBadge</td>" +
            "<td data-label=\"Bank\">${html(credit.bankName)}</td>" +
            "<td data-label=\"Amount\" class=\"num\">${format2(credit.amount)}</td>" +
            "<td data-label=\"Rate\" class=\"num\">${format2(credit.rate)}%</td>" +
            "<td data-label=\"Months\" class=\"num\">${credit.termMonths}</td>" +
            "<td data-label=\"Monthly\" class=\"num\">${format2(credit.monthlyPayment)}</td>" +
            "<td data-label=\"Paid (Debit)\" class=\"num\">${format2(credit.debit)}</td>" +
            "<td data-label=\"Available (Balance)\" class=\"num font-bold\">${format2(credit.balance)}</td>" +
            "<td data-label=\"Remaining\" class=\"num font-bold\">${format2(credit.remainingAmount)}</td>" +
            "<td data-label=\"Status\">$status</td>" +
            "<td data-label=\"Actions\" class=\"actions-cell\"><div class=\"actions-group\">$actionsHtml</div></td>" +
            "</tr>"
    }
    val current = list.firstOrNull()?.budgetAmount ?: ""
    val currentValue = list.firstOrNull()?.budgetAmount ?: 0.0
    val activeCreditList = creditList.filter { it.isActive }
    val salaryCreditFunds = activeCreditList.filter { it.creditType == "SALARY" }.sumOf { it.remainingAmount }
    val productionCreditFunds = activeCreditList.filter { it.creditType == "PRODUCTION" }.sumOf { it.remainingAmount }
    val creditFunds = salaryCreditFunds + productionCreditFunds
    val nonCreditFunds = (currentValue - creditFunds).coerceAtLeast(0.0)
    val bankOptions = CREDIT_BANKS.joinToString("\n") { bank ->
        "<option value=\"${html(bank)}\">${html(bank)}</option>"
    }
    val creditTypeOptions = CREDIT_TYPES.joinToString("\n") { (value, label) ->
        "<option value=\"$value\">$label</option>"
    }
    val budgetFormSection = editFormOrNotice(
        session, MODULE_BUDGET,
        """
        <section class="section-card form-card">
            <h2>Operating Budget</h2>
            <form method="post" action="/budget/save" class="form-simple">
                <div class="form-group">
                    <label>Budget Amount</label>
                    <input type="number" step="0.01" name="amount" value="$current" placeholder="0.00" />
                </div>
                <button type="submit" class="btn-primary">Save Budget</button>
            </form>
        </section>
        """.trimIndent(),
        "Changing the budget is not allowed for your roles."
    )
    val creditFormSection = editFormOrNotice(
        session, MODULE_BUDGET,
        """
        <section class="section-card form-card">
            <h2>New Credit Line</h2>
            <form method="post" action="/credits/save" class="form-grid">
                <div class="form-group">
                    <label>Credit Type</label>
                    <select name="creditType" required>
                        <option value="">Select type</option>
                        $creditTypeOptions
                    </select>
                </div>
                <div class="form-group">
                    <label>Bank</label>
                    <select name="bank" required>
                        <option value="">Select bank</option>
                        $bankOptions
                    </select>
                </div>
                <div class="form-group">
                    <label>Amount</label>
                    <input type="number" step="0.01" name="amount" placeholder="0.00" required />
                </div>
                <div class="form-group">
                    <label>Annual Rate (%)</label>
                    <input type="number" step="0.01" name="rate" placeholder="0.00" required />
                </div>
                <div class="form-group">
                    <label>Term (months)</label>
                    <input type="number" step="1" name="termMonths" placeholder="12" required />
                </div>
                <div class="form-group">
                    <label>Start Date</label>
                    <input type="date" name="startDate" required />
                </div>
                <button type="submit" class="btn-primary">Create Credit</button>
            </form>
        </section>
        """.trimIndent(),
        "Creating credits is not allowed for your roles."
    )
    val content = templates.render(
        "pages/budget.html",
        mapOf(
            "creditFunds" to format2(creditFunds),
            "nonCreditFunds" to format2(nonCreditFunds),
            "salaryCreditFunds" to format2(salaryCreditFunds),
            "productionCreditFunds" to format2(productionCreditFunds),
            "budgetFormSection" to budgetFormSection,
            "creditFormSection" to creditFormSection,
            "creditRows" to creditRows
        )
    )
    return layoutPage("Budget", session, content)
}

internal suspend fun AppServer.adminUsersPage(
    session: AuthSession,
    users: List<UserWithRoles>,
    roles: List<RoleItem>,
    employeesList: List<Employee>,
    edit: UserWithRoles?,
    error: String?
): String {
    val rows = if (users.isEmpty()) {
        "<article class=\"users-empty-state\"><strong>No users</strong></article>"
    } else {
        users.joinToString("\n") { u ->
            val roleLabel = if (u.roles.isEmpty()) "-" else html(u.roles.joinToString(", "))
            val statusLabel = if (u.isActive) "Active" else "Disabled"
            val employeeLabel = u.employeeName?.let { html(it) } ?: "No employee linked"
            val actions = rowActions(session, MODULE_USERS, "/admin/users?editId=${u.id}", "/admin/users/delete", u.id)
            """
            <article class="user-card">
                <h3>${html(u.username)}</h3>
                <div class="user-simple-line">$statusLabel</div>
                <div class="user-simple-line">$employeeLabel</div>
                <div class="user-simple-line">$roleLabel</div>
                <div class="user-card-actions">${if (actions.isBlank()) "<span class=\"user-actions-muted\">No actions available</span>" else actions}</div>
            </article>
            """.trimIndent()
        }
    }
    val employeeOptions = buildString {
        append("<option value=\"\">Not linked</option>")
        employeesList.forEach { employee ->
            val selected = if (edit?.employeeId == employee.id) "selected" else ""
            append("<option value=\"${employee.id}\" $selected>${html(employee.fullName)}</option>")
        }
    }
    val roleChecks = roles.joinToString("\n") { r ->
        val checked = if (edit?.roles?.contains(r.name) == true) "checked" else ""
        "<label><input type=\"checkbox\" name=\"role_${r.id}\" $checked/> ${html(r.name)}</label><br/>"
    }
    val permissionCards = roles.joinToString("\n") { role ->
        val grantedCount = role.permissions.count { it.canEdit || it.canDelete }
        val permissionRows = role.permissions.joinToString("\n") { permission ->
            """
            <div class="role-permission-row">
                <div class="role-permission-module">${html(permission.label)}</div>
                <label class="role-permission-cell">
                    <input type="checkbox" name="${permission.moduleKey}_edit" ${if (permission.canEdit) "checked" else ""}/>
                </label>
                <label class="role-permission-cell">
                    <input type="checkbox" name="${permission.moduleKey}_delete" ${if (permission.canDelete) "checked" else ""}/>
                </label>
            </div>
            """.trimIndent()
        }
        """
        <details class="role-permission-card role-permission-accordion" ${if (role.name.equals("Admin", ignoreCase = true)) "open" else ""}>
            <summary class="role-permission-summary">
                <span class="role-permission-title">${html(role.name)}</span>
                <span class="role-permission-count">$grantedCount/${role.permissions.size}</span>
            </summary>
            <form method="post" action="/admin/roles/permissions/save" class="role-permission-form role-permission-form-stacked">
                <input type="hidden" name="roleId" value="${role.id}" />
                <div class="role-permission-table">
                    <div class="role-permission-row role-permission-row-head">
                        <div class="role-permission-module">Module</div>
                        <div class="role-permission-col-head">Edit</div>
                        <div class="role-permission-col-head">Delete</div>
                    </div>
                    $permissionRows
                </div>
                ${if (canEdit(session, MODULE_USERS)) "<div class=\"role-permission-actions\"><button type=\"submit\" class=\"role-permission-save\">Save</button></div>" else ""}
            </form>
        </details>
        """.trimIndent()
    }
    val userFormSection = editFormOrNotice(
        session, MODULE_USERS,
        """
        <section class="section-card">
            <h2>User account</h2>
            <form method="post" action="/admin/users/save" class="managed-form">
                <input type="hidden" name="id" value="${edit?.id ?: 0}" />
                <div>
                    <label>Username</label>
                    <input name="username" value="${html(edit?.username ?: "")}" />
                </div>
                <div>
                    <label>Password</label>
                    <input name="password" type="password" />
                    <small class="field-hint">Leave empty when editing if the password should remain unchanged.</small>
                </div>
                <div>
                    <label>Employee</label>
                    <select name="employeeId">$employeeOptions</select>
                    <small class="field-hint">Link this user to one employee record from the directory.</small>
                </div>
                <div class="checkbox-field">
                    <label>Active</label>
                    <label class="inline-check"><input type="checkbox" name="isActive" ${if (edit?.isActive != false) "checked" else ""} /> Account is active</label>
                </div>
                <div class="roles-field">
                    <label>Roles</label>
                    <div class="role-checklist">$roleChecks</div>
                    <small class="field-hint">Select only the roles this user should actually have.</small>
                </div>
                <div class="form-actions">
                    <button type="submit">Save user</button>
                    ${cancelEditLink("/admin/users", edit != null)}
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Editing users is not allowed for your roles."
    )
    val rolePermissionsSection = if (roles.isEmpty()) "" else """
        <section class="section-card role-permissions-section">
            <div class="section-head">
                <h2>Role permissions</h2>
            </div>
            <p class="field-hint">Open one role and change access only for the modules that it should manage.</p>
            <div class="role-permissions-grid">
                $permissionCards
            </div>
        </section>
    """.trimIndent()
    val errorBlock = if (error.isNullOrBlank()) "" else "<p style=\"color:red\">${html(error)}</p>"
    val content = templates.render(
        "pages/users.html",
        mapOf(
            "error" to errorBlock,
            "userFormSection" to userFormSection,
            "rolePermissionsSection" to rolePermissionsSection,
            "rows" to rows
        )
    )
    return layoutPage("Users", session, content)
}

internal suspend fun AppServer.productionRequestsPage(
    session: AuthSession,
    list: List<ProductionRequest>,
    productsList: List<FinishedProduct>
): String {
    val requestCards = renderProductionRequestCards(session, list)
    val productOptions = productsList.joinToString("\n") { p ->
        "<option value=\"${p.id}\">${html(p.name)}</option>"
    }
    val formSection = editFormOrNotice(
        session, MODULE_PRODUCTION_REQUESTS,
        """
        <section class="section-card">
            <h2>New production request</h2>
            <p class="field-hint">Submit a request to automatically produce and sell the specified quantity. The system will handle procurement, production, and sales.</p>
            <form method="post" action="/production-requests/submit" class="managed-form">
                <div>
                    <label>Applicant full name</label>
                    <input name="applicantName" />
                </div>
                <div>
                    <label>Product</label>
                    <select name="productId">$productOptions</select>
                </div>
                <div>
                    <label>Quantity</label>
                    <input name="quantity" type="number" step="0.001" min="0.001" />
                </div>
                <div class="form-actions">
                    <button type="submit">Submit request</button>
                </div>
            </form>
        </section>
        """.trimIndent(),
        "Submitting production requests is not allowed for your roles."
    )
    val content = templates.render(
        "pages/production-requests.html",
        mapOf(
            "formSection" to formSection,
            "requestCards" to requestCards,
            "requestCardsSignature" to html(productionRequestSignature(list))
        )
    )
    return layoutPage("Production Requests", session, content)
}

internal fun AppServer.renderProductionRequestCards(
    session: AuthSession,
    list: List<ProductionRequest>
): String {
    val statusClass = { s: String ->
        when (s) {
            RequestStatus.COMPLETED -> "req-status-completed"
            RequestStatus.ERROR -> "req-status-error"
            RequestStatus.PRODUCTION -> "req-status-production"
            RequestStatus.SALES -> "req-status-sales"
            RequestStatus.PROCUREMENT -> "req-status-procurement"
            RequestStatus.RAW_MATERIAL_CHECK -> "req-status-check"
            else -> "req-status-created"
        }
    }
    return if (list.isEmpty()) {
        "<div class=\"req-empty\">No production requests yet. Submit the first one using the form.</div>"
    } else {
        list.joinToString("\n") { r ->
            val statusBadge = "<span class=\"req-status-badge ${statusClass(r.status)}\">${html(r.status)}</span>"
            val rejectionBlock = if (!r.rejectionReason.isNullOrBlank())
                "<div class=\"req-rejection\"><span class=\"req-rejection-label\">Rejection reason:</span> ${html(r.rejectionReason)}</div>" else ""
            val processAction = if (r.status == RequestStatus.CREATED && canEdit(session, MODULE_PRODUCTION_REQUESTS)) {
                "<form method=\"post\" action=\"/production-requests/process\" class=\"action-form\">" +
                    "<input type=\"hidden\" name=\"id\" value=\"${r.id}\"/>" +
                    "<button type=\"submit\" class=\"action-link action-link-edit\">Process</button></form>"
            } else ""
            val deleteAction = if (canDelete(session, MODULE_PRODUCTION_REQUESTS)) {
                "<form method=\"post\" action=\"/production-requests/delete\" class=\"action-form\">" +
                    "<input type=\"hidden\" name=\"id\" value=\"${r.id}\"/>" +
                    "<button type=\"submit\" class=\"action-link action-link-delete\">Delete</button></form>"
            } else ""
            """
            <article class="req-card">
                <div class="req-card-header">
                    <div class="req-card-meta">
                        <span class="req-id">Request #${r.id}</span>
                        <span class="req-date">${r.createdAt.toLocalDate()}</span>
                    </div>
                    $statusBadge
                </div>
                <div class="req-card-body">
                    <div class="req-field">
                        <span class="req-field-label">Applicant</span>
                        <span class="req-field-value">${html(r.applicantName)}</span>
                    </div>
                    <div class="req-field">
                        <span class="req-field-label">Product</span>
                        <span class="req-field-value">${html(r.productName ?: r.productId.toString())}</span>
                    </div>
                    <div class="req-field">
                        <span class="req-field-label">Quantity</span>
                        <span class="req-field-value">${format3(r.quantity)}</span>
                    </div>
                    <div class="req-field">
                        <span class="req-field-label">Updated</span>
                        <span class="req-field-value">${r.updatedAt.toLocalDate()}</span>
                    </div>
                </div>
                $rejectionBlock
                ${if (processAction.isNotBlank() || deleteAction.isNotBlank()) "<div class=\"req-card-actions\">${processAction}${deleteAction}</div>" else ""}
            </article>
            """.trimIndent()
        }
    }
}

internal fun productionRequestSignature(list: List<ProductionRequest>): String =
    list.joinToString("|") { request ->
        "${request.id}:${request.status}:${request.updatedAt}:${request.rejectionReason.orEmpty()}"
    }

private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

internal fun AppServer.publicRequestPage(
    productsList: List<FinishedProduct>,
    submittedId: Int?,
    queriedId: Int?,
    queriedRequest: ProductionRequest?
): String {
    val productOptions = productsList.joinToString("\n") { p ->
        "<option value=\"${p.id}\">${html(p.name)}</option>"
    }

    val notification = when {
        submittedId != null -> """
            <div class="pr-notification pr-notify-success">
                <i class="fa-solid fa-circle-check pr-notify-icon"></i>
                <div class="pr-notify-body">
                    <strong>Request #$submittedId submitted successfully!</strong>
                    <p>Your production request has been received. Staff will review and process it shortly.</p>
                    <div class="pr-notify-actions">
                        <a href="/request/status?id=$submittedId" class="pr-notify-btn">
                            <i class="fa-solid fa-magnifying-glass"></i> Check status
                        </a>
                        <a href="/request" class="pr-notify-btn">
                            <i class="fa-solid fa-plus"></i> New request
                        </a>
                    </div>
                </div>
            </div>
        """.trimIndent()
        else -> ""
    }

    val statusResult = when {
        queriedId != null && queriedRequest == null ->
            "<div class=\"pr-status-not-found\"><i class=\"fa-solid fa-circle-xmark\"></i> Request #$queriedId not found.</div>"
        queriedRequest != null -> {
            val statusClass = when (queriedRequest.status) {
                RequestStatus.COMPLETED -> "req-status-completed"
                RequestStatus.ERROR -> "req-status-error"
                RequestStatus.PRODUCTION -> "req-status-production"
                RequestStatus.SALES -> "req-status-sales"
                RequestStatus.PROCUREMENT -> "req-status-procurement"
                RequestStatus.RAW_MATERIAL_CHECK -> "req-status-check"
                else -> "req-status-created"
            }
            val rejRow = if (!queriedRequest.rejectionReason.isNullOrBlank())
                "<div class=\"pr-status-result-row\"><span class=\"pr-status-result-label\">Reason</span><span class=\"pr-status-result-value\" style=\"color:var(--danger)\">${html(queriedRequest.rejectionReason)}</span></div>"
            else ""
            """
            <div class="pr-status-result">
                <div class="pr-status-result-row">
                    <span class="pr-status-result-label">Request ID</span>
                    <span class="pr-status-result-value">#${queriedRequest.id}</span>
                </div>
                <div class="pr-status-result-row">
                    <span class="pr-status-result-label">Status</span>
                    <span class="pr-status-result-value"><span class="req-status-badge $statusClass">${html(queriedRequest.status)}</span></span>
                </div>
                <div class="pr-status-result-row">
                    <span class="pr-status-result-label">Product</span>
                    <span class="pr-status-result-value">${html(queriedRequest.productName ?: queriedRequest.productId.toString())}</span>
                </div>
                <div class="pr-status-result-row">
                    <span class="pr-status-result-label">Quantity</span>
                    <span class="pr-status-result-value">${format3(queriedRequest.quantity)}</span>
                </div>
                <div class="pr-status-result-row">
                    <span class="pr-status-result-label">Applicant</span>
                    <span class="pr-status-result-value">${html(queriedRequest.applicantName)}</span>
                </div>
                <div class="pr-status-result-row">
                    <span class="pr-status-result-label">Submitted</span>
                    <span class="pr-status-result-value">${queriedRequest.createdAt.format(dateFmt)}</span>
                </div>
                $rejRow
            </div>
            """.trimIndent()
        }
        else -> ""
    }

    return templates.render(
        "public-request.html",
        mapOf(
            "productOptions" to productOptions,
            "notification" to notification,
            "statusResult" to statusResult
        )
    )
}

internal suspend fun AppServer.historyPage(session: AuthSession, list: List<Transaction>): String {
    val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val rows = list.joinToString("\n") { t ->
        "<tr>" +
            "<td>${t.id}</td>" +
            "<td>${html(t.type.name)}</td>" +
            "<td>${html(t.description)}</td>" +
            "<td>${format2(t.amount)}</td>" +
            "<td>${format2(t.debit)}</td>" +
            "<td>${format2(t.balance)}</td>" +
            "<td>${t.createdAt.format(dateFmt)}</td>" +
            "</tr>"
    }
    val noData = if (list.isEmpty()) {
        "<tr><td colspan=\"7\" style=\"text-align: center; color: #999;\">No transactions recorded yet</td></tr>"
    } else ""
    val transactionTable = if (list.isNotEmpty()) rows else noData

    val content = """
        <h1>Transaction History</h1>
        <p>Audit log of all financial transactions in the system.</p>
        <table class="data-table">
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Type</th>
                    <th>Description</th>
                    <th>Amount</th>
                    <th>Debit</th>
                    <th>Balance</th>
                    <th>Created</th>
                </tr>
            </thead>
            <tbody>
                $transactionTable
            </tbody>
        </table>
    """.trimIndent()

    return layoutPage("Transaction History", session, content)
}

internal suspend fun AppServer.documentsPage(session: AuthSession, list: List<Document>): String {
    val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val rows = list.joinToString("\n") { d ->
        "<tr>" +
            "<td>${d.id}</td>" +
            "<td>${html(d.type.name)}</td>" +
            "<td>${html(d.title)}</td>" +
            "<td>${format2(d.amount)}</td>" +
            "<td>${html(d.description)}</td>" +
            "<td>${d.createdAt.format(dateFmt)}</td>" +
            "<td><a href=\"#trans-${d.transactionId}\" style=\"font-size: 0.9em;\">Ref: ${d.transactionId}</a></td>" +
            "</tr>"
    }
    val noData = if (list.isEmpty()) {
        "<tr><td colspan=\"7\" style=\"text-align: center; color: #999;\">No documents recorded yet</td></tr>"
    } else ""
    val documentTable = if (list.isNotEmpty()) rows else noData

    val content = """
        <h1>Documents</h1>
        <p>Auto-generated documents for purchases, production, sales, and credit transactions.</p>
        <table class="data-table">
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Type</th>
                    <th>Title</th>
                    <th>Amount</th>
                    <th>Description</th>
                    <th>Created</th>
                    <th>Transaction</th>
                </tr>
            </thead>
            <tbody>
                $documentTable
            </tbody>
        </table>
    """.trimIndent()

    return layoutPage("Documents", session, content)
}
