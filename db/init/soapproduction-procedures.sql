USE [SoapProduction];
GO

CREATE OR ALTER PROCEDURE sp_SetupAuthProcedures
AS
BEGIN
    SET NOCOUNT ON;

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_SaveRolePermissions
        @RoleId    INT,
        @ModuleKey NVARCHAR(100),
        @CanEdit   BIT,
        @CanDelete BIT
    AS
    BEGIN
        SET NOCOUNT ON;
        IF EXISTS (
            SELECT 1 FROM RolePermissions
            WHERE RoleId = @RoleId AND ModuleKey = @ModuleKey
        )
            UPDATE RolePermissions
            SET CanEdit = @CanEdit, CanDelete = @CanDelete
            WHERE RoleId = @RoleId AND ModuleKey = @ModuleKey;
        ELSE
            INSERT INTO RolePermissions (RoleId, ModuleKey, CanEdit, CanDelete)
            VALUES (@RoleId, @ModuleKey, @CanEdit, @CanDelete);
    END
    ');
END
GO

CREATE OR ALTER PROCEDURE sp_SetupCreditProcedures
AS
BEGIN
    SET NOCOUNT ON;

    -- Migrate: add CreditType column if missing
    IF NOT EXISTS (
        SELECT 1 FROM sys.columns
        WHERE object_id = OBJECT_ID(N'Credits') AND name = N'CreditType'
    )
    BEGIN
        ALTER TABLE Credits ADD CreditType NVARCHAR(50) NOT NULL DEFAULT N'PRODUCTION';
    END;

    -- Migrate: add Debit column if missing
    IF NOT EXISTS (
        SELECT 1 FROM sys.columns
        WHERE object_id = OBJECT_ID(N'Credits') AND name = N'Debit'
    )
    BEGIN
        ALTER TABLE Credits ADD Debit FLOAT NOT NULL DEFAULT 0.0;
    END;

    -- Migrate: add Balance column if missing
    IF NOT EXISTS (
        SELECT 1 FROM sys.columns
        WHERE object_id = OBJECT_ID(N'Credits') AND name = N'Balance'
    )
    BEGIN
        ALTER TABLE Credits ADD Balance FLOAT NOT NULL DEFAULT 0.0;
    END;

    -- Create Transactions table if missing
    IF NOT EXISTS (
        SELECT 1 FROM sys.tables WHERE name = 'Transactions'
    )
    BEGIN
        CREATE TABLE Transactions (
            Id INT IDENTITY(1,1) PRIMARY KEY,
            Type NVARCHAR(100) NOT NULL,
            Description NVARCHAR(MAX) NOT NULL,
            Amount FLOAT NOT NULL,
            Debit FLOAT NOT NULL,
            Balance FLOAT NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
            RelatedEntityId INT NULL,
            DocumentId INT NULL
        );
    END;

    -- Create Documents table if missing
    IF NOT EXISTS (
        SELECT 1 FROM sys.tables WHERE name = 'Documents'
    )
    BEGIN
        CREATE TABLE Documents (
            Id INT IDENTITY(1,1) PRIMARY KEY,
            Type NVARCHAR(100) NOT NULL,
            Title NVARCHAR(MAX) NOT NULL,
            Amount FLOAT NOT NULL,
            Description NVARCHAR(MAX) NOT NULL,
            CreatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
            TransactionId INT NOT NULL,
            FOREIGN KEY (TransactionId) REFERENCES Transactions(Id)
        );
    END;

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_AddCredit
        @BankName   NVARCHAR(200),
        @Amount     FLOAT,
        @Rate       FLOAT,
        @TermMonths INT,
        @StartDate  DATE,
        @CreditType NVARCHAR(50)
    AS
    BEGIN
        SET NOCOUNT ON;
        DECLARE @MonthlyRate FLOAT = @Rate / 100.0 / 12.0;
        DECLARE @MonthlyPayment FLOAT;

        IF @MonthlyRate = 0 OR @TermMonths = 0
            SET @MonthlyPayment = CASE
                WHEN @TermMonths > 0 THEN @Amount / @TermMonths
                ELSE @Amount
            END;
        ELSE
            SET @MonthlyPayment = @Amount * @MonthlyRate * POWER(1.0 + @MonthlyRate, @TermMonths)
                                 / (POWER(1.0 + @MonthlyRate, @TermMonths) - 1.0);

        INSERT INTO Credits (
            BankName, Amount, Rate, TermMonths, StartDate,
            MonthlyPayment, RemainingAmount, IsActive, CreditType
        )
        VALUES (
            @BankName, @Amount, @Rate, @TermMonths, @StartDate,
            @MonthlyPayment, @Amount, 1, @CreditType
        );

        SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_ListCredits
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT Id, BankName, Amount, Rate, TermMonths, StartDate,
               MonthlyPayment, RemainingAmount, IsActive, CreditType
        FROM Credits
        ORDER BY Id DESC;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_PayCredit
        @Id            INT,
        @PaymentAmount FLOAT
    AS
    BEGIN
        SET NOCOUNT ON;
        UPDATE Credits
        SET RemainingAmount = CASE
                WHEN RemainingAmount - @PaymentAmount <= 0 THEN 0
                ELSE RemainingAmount - @PaymentAmount
            END,
            IsActive = CASE
                WHEN RemainingAmount - @PaymentAmount <= 0 THEN 0
                ELSE IsActive
            END
        WHERE Id = @Id AND IsActive = 1;
        SELECT @Id AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_PayCreditMonthly
        @Id INT
    AS
    BEGIN
        SET NOCOUNT ON;
        UPDATE Credits
        SET RemainingAmount = CASE
                WHEN RemainingAmount - MonthlyPayment <= 0 THEN 0
                ELSE RemainingAmount - MonthlyPayment
            END,
            IsActive = CASE
                WHEN RemainingAmount - MonthlyPayment <= 0 THEN 0
                ELSE IsActive
            END
        WHERE Id = @Id AND IsActive = 1;
        SELECT @Id AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_DeleteCredit
        @Id INT
    AS
    BEGIN
        SET NOCOUNT ON;
        DELETE FROM Credits WHERE Id = @Id;
        SELECT 0 AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_CreateTransaction
        @Type NVARCHAR(100),
        @Description NVARCHAR(MAX),
        @Amount FLOAT,
        @Debit FLOAT,
        @Balance FLOAT,
        @RelatedEntityId INT = NULL,
        @DocumentId INT = NULL
    AS
    BEGIN
        SET NOCOUNT ON;
        INSERT INTO Transactions (Type, Description, Amount, Debit, Balance, CreatedAt, RelatedEntityId, DocumentId)
        VALUES (@Type, @Description, @Amount, @Debit, @Balance, GETDATE(), @RelatedEntityId, @DocumentId);
        SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_ListTransactions
        @Type NVARCHAR(100) = NULL
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT Id, Type, Description, Amount, Debit, Balance, CreatedAt, RelatedEntityId, DocumentId
        FROM Transactions
        WHERE @Type IS NULL OR Type = @Type
        ORDER BY CreatedAt DESC;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_CreateDocument
        @Type NVARCHAR(100),
        @Title NVARCHAR(MAX),
        @Amount FLOAT,
        @Description NVARCHAR(MAX),
        @TransactionId INT
    AS
    BEGIN
        SET NOCOUNT ON;
        INSERT INTO Documents (Type, Title, Amount, Description, CreatedAt, TransactionId)
        VALUES (@Type, @Title, @Amount, @Description, GETDATE(), @TransactionId);
        SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_ListDocuments
        @Type NVARCHAR(100) = NULL
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT Id, Type, Title, Amount, Description, CreatedAt, TransactionId
        FROM Documents
        WHERE @Type IS NULL OR Type = @Type
        ORDER BY CreatedAt DESC;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_UpdateCreditDebit
        @Id INT,
        @PaymentAmount FLOAT
    AS
    BEGIN
        SET NOCOUNT ON;
        DECLARE @CurrentDebit FLOAT;
        DECLARE @Amount FLOAT;

        SELECT @CurrentDebit = Debit, @Amount = Amount
        FROM Credits
        WHERE Id = @Id;

        IF @CurrentDebit IS NOT NULL
        BEGIN
            UPDATE Credits
            SET Debit = @CurrentDebit + @PaymentAmount,
                Balance = @Amount - (@CurrentDebit + @PaymentAmount),
                RemainingAmount = @Amount - (@CurrentDebit + @PaymentAmount),
                IsActive = CASE
                    WHEN @Amount - (@CurrentDebit + @PaymentAmount) <= 0 THEN 0
                    ELSE IsActive
                END
            WHERE Id = @Id;
        END;
        SELECT @Id AS Id;
    END
    ');
END
GO

CREATE OR ALTER PROCEDURE sp_EnsureProductionRequestSchema
AS
BEGIN
    SET NOCOUNT ON;

    IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'ProductionRequests')
    BEGIN
        CREATE TABLE ProductionRequests (
            Id INT IDENTITY(1,1) PRIMARY KEY,
            CreatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
            UpdatedAt DATETIME2 NOT NULL DEFAULT GETDATE(),
            Status NVARCHAR(100) NOT NULL DEFAULT N'Created',
            ApplicantName NVARCHAR(200) NOT NULL,
            ProductId INT NOT NULL,
            Quantity FLOAT NOT NULL DEFAULT 1,
            RejectionReason NVARCHAR(MAX) NULL
        );
    END;

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_ListProductionRequests
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT
            r.Id,
            r.CreatedAt,
            r.UpdatedAt,
            r.Status,
            r.ApplicantName,
            r.ProductId,
            r.Quantity,
            r.RejectionReason,
            p.Name AS ProductName
        FROM ProductionRequests r
        LEFT JOIN FinishedProducts p ON p.Id = r.ProductId
        ORDER BY r.CreatedAt DESC;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_GetProductionRequest
        @Id INT
    AS
    BEGIN
        SET NOCOUNT ON;
        SELECT
            r.Id,
            r.CreatedAt,
            r.UpdatedAt,
            r.Status,
            r.ApplicantName,
            r.ProductId,
            r.Quantity,
            r.RejectionReason,
            p.Name AS ProductName
        FROM ProductionRequests r
        LEFT JOIN FinishedProducts p ON p.Id = r.ProductId
        WHERE r.Id = @Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_CreateProductionRequest
        @ApplicantName NVARCHAR(200),
        @ProductId INT,
        @Quantity FLOAT
    AS
    BEGIN
        SET NOCOUNT ON;
        INSERT INTO ProductionRequests (
            ApplicantName, ProductId, Quantity, Status, CreatedAt, UpdatedAt
        )
        VALUES (
            @ApplicantName, @ProductId, @Quantity, N''Created'', GETDATE(), GETDATE()
        );

        SELECT CAST(SCOPE_IDENTITY() AS INT) AS Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_UpdateProductionRequestStatus
        @Id INT,
        @Status NVARCHAR(100),
        @RejectionReason NVARCHAR(MAX) = NULL
    AS
    BEGIN
        SET NOCOUNT ON;
        UPDATE ProductionRequests
        SET Status = @Status,
            RejectionReason = @RejectionReason,
            UpdatedAt = GETDATE()
        WHERE Id = @Id;
    END
    ');

    EXEC(N'
    CREATE OR ALTER PROCEDURE sp_DeleteProductionRequest
        @Id INT
    AS
    BEGIN
        SET NOCOUNT ON;
        DELETE FROM ProductionRequests WHERE Id = @Id;
    END
    ');
END
GO
