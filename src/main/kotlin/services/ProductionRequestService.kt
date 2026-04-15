package services

import models.RequestStatus
import repositories.*
import java.time.LocalDateTime

class ProductionRequestService(
    private val requestRepo: ProductionRequestRepository,
    private val ingredientRepo: IngredientRepository,
    private val rawMaterialRepo: RawMaterialRepository,
    private val budgetRepo: BudgetRepository,
    private val purchaseRepo: PurchaseRawMaterialRepository,
    private val productionRepo: ProductProductionRepository,
    private val salesRepo: ProductSalesRepository,
    private val employeeRepo: EmployeeRepository,
    private val productRepo: FinishedProductRepository
) {
    suspend fun ensureSchema() = requestRepo.ensureSchema()

    suspend fun listAll() = requestRepo.listAll()

    suspend fun getById(id: Int) = requestRepo.getById(id)

    suspend fun delete(id: Int) = requestRepo.delete(id)

    suspend fun submit(applicantName: String, productId: Int, quantity: Double): Int {
        if (applicantName.isBlank()) throw ValidationException("Applicant name is required")
        if (quantity <= 0.0) throw ValidationException("Quantity must be greater than 0")
        val id = requestRepo.create(applicantName, productId, quantity)
        processRequest(id)
        return id
    }

    suspend fun createOnly(applicantName: String, productId: Int, quantity: Double): Int {
        if (applicantName.isBlank()) throw ValidationException("Applicant name is required")
        if (quantity <= 0.0) throw ValidationException("Quantity must be greater than 0")
        return requestRepo.create(applicantName, productId, quantity)
    }

    suspend fun processById(id: Int) {
        val request = requestRepo.getById(id) ?: throw ValidationException("Request #$id not found")
        if (request.status != RequestStatus.CREATED) throw ValidationException("Request is already being processed (status: ${request.status})")
        processRequest(id)
    }

    private suspend fun setStatus(id: Int, status: String, reason: String? = null) {
        requestRepo.updateStatus(id, status, reason)
    }

    private suspend fun setError(id: Int, reason: String) {
        requestRepo.updateStatus(id, RequestStatus.ERROR, reason)
    }

    private suspend fun processRequest(id: Int) {
        val request = requestRepo.getById(id) ?: return

        // --- Stage 1: Check raw material availability ---
        setStatus(id, RequestStatus.RAW_MATERIAL_CHECK)
        val ingredients = ingredientRepo.listAll().filter { it.productId == request.productId }
        if (ingredients.isEmpty()) {
            setError(id, "No ingredients defined for product ${request.productId}"); return
        }
        val rawMaterials = rawMaterialRepo.listAll().associateBy { it.id }

        data class Need(val rawMaterialId: Int, val required: Double, val available: Double, val unitCost: Double)
        val needs = ingredients.map { ing ->
            val rm = rawMaterials[ing.rawMaterialId]
            val required = ing.quantity * request.quantity
            val available = rm?.quantity ?: 0.0
            val unitCost = if ((rm?.quantity ?: 0.0) > 0.0) rm!!.amount / rm.quantity else 1.0
            Need(ing.rawMaterialId, required, available, unitCost)
        }

        val shortfalls = needs.filter { it.required > it.available }

        // --- Stage 2: Procurement if needed ---
        if (shortfalls.isNotEmpty()) {
            setStatus(id, RequestStatus.PROCUREMENT)
            val totalCost = shortfalls.sumOf { (it.required - it.available) * it.unitCost }

            val budget = budgetRepo.listAll().firstOrNull()
            val available = budget?.budgetAmount ?: 0.0
            if (available < totalCost) {
                setError(id, "Insufficient budget: required %.2f, available %.2f".format(totalCost, available))
                return
            }

            // Deduct from budget
            budgetRepo.create(available - totalCost)

            // Get system employee (first available)
            val systemEmployee = employeeRepo.listAll().firstOrNull()
            val employeeId = systemEmployee?.id ?: 1
            val now = LocalDateTime.now()

            // Purchase each missing material
            for (sh in shortfalls) {
                val missingQty = sh.required - sh.available
                purchaseRepo.create(sh.rawMaterialId, missingQty, missingQty * sh.unitCost, now, employeeId)
            }
        }

        // --- Stage 3: Production ---
        setStatus(id, RequestStatus.PRODUCTION)
        val systemEmployee = employeeRepo.listAll().firstOrNull()
        val employeeId = systemEmployee?.id ?: 1
        val now = LocalDateTime.now()
        productionRepo.create(request.productId, request.quantity, now, employeeId)

        // --- Stage 4: Sales with 30% markup ---
        setStatus(id, RequestStatus.SALES)
        salesRepo.create(request.productId, request.quantity, now, employeeId)

        // --- Completed ---
        setStatus(id, RequestStatus.COMPLETED)
    }
}
