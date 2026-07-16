package com.the1mason.geckonomy.application.result

import com.the1mason.geckonomy.domain.model.Money

/**
 * What a single-account operation answers with: the balance **after** the change, or why there wasn't
 * one (SPEC.md FR-B3).
 *
 * [Money] rather than a bare `BigDecimal` because the balance travels to a renderer that needs the
 * currency to format it — and because an amount without its currency is the bug `Money` exists to
 * prevent (DOMAIN_MODEL.md §1).
 */
typealias OperationResult = Outcome<Money>
