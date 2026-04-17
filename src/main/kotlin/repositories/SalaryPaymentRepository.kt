package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.SalaryPayment
import java.time.LocalDateTime

class SalaryPaymentRepository(private val pool: Pool) {

    suspend fun listAll(): List<SalaryPayment> {
        val rows = pool.query("EXEC sp_ListSalaryPayments").execute().coAwait()
        return rows.map { row ->
            SalaryPayment(
                id = row.requireInt("Id"), employeeId = row.requireInt("EmployeeID"),
                amount = row.requireDouble("Amount"), paymentDate = row.requireLocalDateTime("PaymentDate"),
                note = row.getStringSafe("Note"), employeeName = row.getStringSafe("EmployeeName")
            )
        }
    }

    suspend fun create(employeeId: Int, amount: Double?, paymentDate: LocalDateTime, note: String?): Int {
        val rows = pool.preparedQuery("EXEC sp_AddSalaryPayment ?, ?, ?, ?")
            .execute(Tuple.of(employeeId, amount, paymentDate, note)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteSalaryPayment ?").execute(Tuple.of(id)).coAwait().rowCount()

    suspend fun deleteLast() {
        pool.query("EXEC sp_DeleteLastSalaryPayment").execute().coAwait()
    }
}

