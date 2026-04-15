package repositories

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import models.Credit
import java.time.LocalDate

class CreditRepository(private val pool: Pool) {

    suspend fun ensureSchema() {
        pool.query("EXEC sp_EnsureCreditSchema").execute().coAwait()
        // Override sp_AddCredit with annuity formula:
        // M = P * (r/12) * (1 + r/12)^n / ((1 + r/12)^n - 1)
        pool.query(
            """
            CREATE OR ALTER PROCEDURE sp_AddCredit
                @BankName  NVARCHAR(200),
                @Amount    FLOAT,
                @Rate      FLOAT,
                @TermMonths INT,
                @StartDate DATE
            AS
            BEGIN
                SET NOCOUNT ON;
                DECLARE @MonthlyRate FLOAT = @Rate / 100.0 / 12.0;
                DECLARE @MonthlyPayment FLOAT;
                IF @MonthlyRate = 0 OR @TermMonths = 0
                    SET @MonthlyPayment = CASE WHEN @TermMonths > 0 THEN @Amount / @TermMonths ELSE @Amount END;
                ELSE
                    SET @MonthlyPayment = @Amount * @MonthlyRate * POWER(1.0 + @MonthlyRate, @TermMonths)
                                         / (POWER(1.0 + @MonthlyRate, @TermMonths) - 1.0);
                INSERT INTO Credits (BankName, Amount, Rate, TermMonths, StartDate, MonthlyPayment, RemainingAmount, IsActive)
                VALUES (@BankName, @Amount, @Rate, @TermMonths, @StartDate, @MonthlyPayment, @Amount, 1);
                SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
            END
        """.trimIndent()
        ).execute().coAwait()
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
