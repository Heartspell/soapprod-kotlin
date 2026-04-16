package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Credit
import java.time.LocalDate

class CreditRepository(private val pool: Pool) {

    suspend fun ensureSchema() {
        pool.query("EXEC sp_EnsureCreditSchema").execute().coAwait()
        pool.query("EXEC sp_SetupCreditProcedures").execute().coAwait()
    }

    suspend fun listAll(): List<Credit> {
        val rows = pool.query("EXEC sp_ListCredits").execute().coAwait()
        return rows.map { row ->
            Credit(
                id = row.requireInt("Id"), bankName = row.requireString("BankName"),
                amount = row.requireDouble("Amount"), rate = row.requireDouble("Rate"),
                termMonths = row.requireInt("TermMonths"), startDate = row.requireLocalDate("StartDate"),
                monthlyPayment = row.requireDouble("MonthlyPayment"),
                remainingAmount = row.requireDouble("RemainingAmount"),
                isActive = when (val v = row.getValueAny("IsActive")) {
                    is Boolean -> v
                    is Number -> v.toInt() != 0
                    else -> false
                }
            )
        }
    }

    suspend fun getById(id: Int): Credit? = listAll().firstOrNull { it.id == id }

    suspend fun create(bankName: String, amount: Double, rate: Double, termMonths: Int, startDate: LocalDate): Int {
        val rows = pool.preparedQuery("EXEC sp_AddCredit ?, ?, ?, ?, ?")
            .execute(Tuple.of(bankName, amount, rate, termMonths, startDate)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else 0
    }

    suspend fun pay(id: Int, paymentAmount: Double): Int {
        val rows = pool.preparedQuery("EXEC sp_PayCredit ?, ?").execute(Tuple.of(id, paymentAmount)).coAwait()
        val it = rows.iterator()
        return if (it.hasNext()) it.next().requireInt("Id") else id
    }

    suspend fun delete(id: Int): Int =
        pool.preparedQuery("EXEC sp_DeleteCredit ?").execute(Tuple.of(id)).coAwait().rowCount()
}
