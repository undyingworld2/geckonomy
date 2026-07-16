# application.usecase

One class per operation, verb-first: `CreateAccount`, `GetBalance`, `Has`, `Deposit`, `Withdraw`,
`SetBalance`, `Transfer`, `RenameAccount`, `DeleteAccount`, `ListCurrencies`, `FormatMoney`.

Each takes its ports via the constructor and is tested against fakes. `Transfer` is the one that must
run inside `UnitOfWork.transaction { }` so both legs and both ledger rows commit or roll back together.

Arrives with **M4**.
