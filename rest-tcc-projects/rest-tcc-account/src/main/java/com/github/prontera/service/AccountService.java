package com.github.prontera.service;

import com.github.prontera.Shifts;
import com.github.prontera.account.enums.ReservingState;
import com.github.prontera.account.enums.StatusCode;
import com.github.prontera.account.model.request.BalanceReservingRequest;
import com.github.prontera.account.model.request.ConfirmAccountTxnRequest;
import com.github.prontera.account.model.request.QueryAccountRequest;
import com.github.prontera.account.model.response.BalanceReservingResponse;
import com.github.prontera.account.model.response.ConfirmAccountTxnResponse;
import com.github.prontera.account.model.response.QueryAccountResponse;
import com.github.prontera.domain.Account;
import com.github.prontera.domain.AccountTransaction;
import com.github.prontera.persistence.AccountMapper;
import com.github.prontera.persistence.AccountTransactionMapper;
import com.github.prontera.util.Responses;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Zhao Junjian
 * @date 2020/01/22
 */
@Service
public class AccountService {

    public static final int MAX_RETRY_CONFIRM_TIMES = 3;

    private final AccountMapper mapper;

    private final AccountTransactionMapper transactionMapper;

    private final TransactionTemplate transactionTemplate;

    @Lazy
    @Autowired
    public AccountService(@Nonnull AccountMapper mapper,
                          @Nonnull AccountTransactionMapper transactionMapper,
                          @Nonnull PlatformTransactionManager transactionManager) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactionMapper = Objects.requireNonNull(transactionMapper);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * ?????????????????????ID
     */
    public QueryAccountResponse queryByUsername(@Nonnull QueryAccountRequest request) {
        Objects.requireNonNull(request);
        final String username = StringUtils.trimToEmpty(request.getName());
        final Optional<Account> nullableAccount = findByUsername(username);
        if (!nullableAccount.isPresent()) {
            Shifts.fatal(StatusCode.USER_NOT_EXISTS);
        }
        final Account account = nullableAccount.get();
        final QueryAccountResponse response = Responses.generate(QueryAccountResponse.class, StatusCode.OK);
        response.setId(account.getId());
        response.setName(account.getName());
        return response;
    }

    /**
     * 1. ??????username??????Account
     * 2. ??????orderId????????????????????????AccountTransaction
     * --- a. ????????????, ??????intermediate state, ???????????????reservingSecond?????????, ??????try??????
     * --- b. ????????????, ??????final state, ??????data violation, ????????????
     * --- c. ???????????????, ??????????????????
     */
    public BalanceReservingResponse reserving(@Nonnull BalanceReservingRequest request) {
        Objects.requireNonNull(request);
        // find by username
        final String username = StringUtils.trimToEmpty(request.getUsername());
        final Optional<Account> nullableAccount = findByUsername(username);
        if (!nullableAccount.isPresent()) {
            Shifts.fatal(StatusCode.USER_NOT_EXISTS);
        }
        // according to order id, retrieve and check if any transaction existed
        final Long orderId = request.getOrderId();
        final BalanceReservingResponse response;
        final Optional<AccountTransaction> nullableAccountTransaction = Optional.ofNullable(transactionMapper.selectByOrderId(orderId));
        if (nullableAccountTransaction.isPresent()) {
            final AccountTransaction accountTransaction = nullableAccountTransaction.get();
            response = recoverTransaction(orderId, accountTransaction);
        } else {
            final Account account = nullableAccount.get();
            final Long accountId = account.getId();
            final Integer amount = request.getAmount();
            final Integer reservingSeconds = request.getExpectedReservingSeconds();
            response = newTransaction(orderId, accountId, amount, reservingSeconds);
        }
        return response;
    }

    private BalanceReservingResponse newTransaction(Long orderId, Long accountId, Integer deductAmount, Integer reservingSeconds) {
        return transactionTemplate.execute(status -> {
            // did not throw any exception within TransactionTemplate
            final BalanceReservingResponse response;
            if (mapper.deductBalance(accountId, (long) deductAmount) > 0) {
                final AccountTransaction accountTransaction = new AccountTransaction();
                accountTransaction.setOrderId(orderId);
                accountTransaction.setUserId(accountId);
                accountTransaction.setAmount(Long.valueOf(deductAmount));
                accountTransaction.setState(ReservingState.TRYING);
                accountTransaction.setCreateAt(LocalDateTime.now());
                accountTransaction.setUpdateAt(LocalDateTime.now());
                accountTransaction.setExpireAt(LocalDateTime.now().plusSeconds(reservingSeconds));
                transactionMapper.insertSelective(accountTransaction);
                response = Responses.generate(BalanceReservingResponse.class, StatusCode.OK);
            } else {
                response = Responses.generate(BalanceReservingResponse.class, StatusCode.INSUFFICIENT_BALANCE);
            }
            return response;
        });
    }

    private BalanceReservingResponse recoverTransaction(Long orderId, AccountTransaction accountTransaction) {
        final ReservingState reservingState = accountTransaction.getState();
        final BalanceReservingResponse response;
        if (reservingState == ReservingState.TRYING) {
            final long expiredSeconds = Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), accountTransaction.getExpireAt()));
            if (expiredSeconds <= 0) {
                // auto cancellation
                cancellableFindTransaction(orderId);
                Shifts.fatal(StatusCode.TIMEOUT_AND_CANCELLED);
            }
            response = Responses.generate(BalanceReservingResponse.class, StatusCode.IDEMPOTENT_RESERVING);
        } else if (reservingState == ReservingState.INVALID) {
            response = Responses.generate(BalanceReservingResponse.class, StatusCode.UNKNOWN_RESERVING_STATE);
        } else {
            response = Responses.generate(BalanceReservingResponse.class, StatusCode.RESERVING_FINAL_STATE);
        }
        return response;
    }

    /**
     * ??????orderId??????????????????, ???????????????, ?????????????????????????????????, ???????????????????????????
     */
    public Optional<AccountTransaction> cancellableFindTransaction(long orderId) {
        AccountTransaction transaction = transactionMapper.selectByOrderId(orderId);
        if (transaction != null) {
            final LocalDateTime now = LocalDateTime.now();
            if (transaction.getState() == ReservingState.TRYING && now.isAfter(transaction.getExpireAt())) {
                transactionTemplate.execute(status -> {
                    transaction.setState(ReservingState.CANCELLED);
                    transaction.setDoneAt(now);
                    if (transactionMapper.compareAndSetState(transaction.getId(), ReservingState.TRYING, ReservingState.CANCELLED) <= 0) {
                        // ATTENTION: u should force to retrieve from master node in production environment.
                        return transactionMapper.selectByOrderId(orderId);
                    }
                    Long id = transaction.getUserId();
                    if (mapper.increaseBalance(id, transaction.getAmount()) <= 0) {
                        Shifts.fatal(StatusCode.ACCOUNT_ROLLBACK_FAILURE);
                    }
                    return transaction;
                });
            }
        }
        return Optional.ofNullable(transaction);
    }

    /**
     * 1. ??????auto-cancellation???????????????, ??????orderId??????????????????,
     * 2. ?????????trying??????, ???????????????, ???????????????
     * 3. ?????????final state
     * --- a. ?????????cancel, ????????????????????????
     * --- b. ???????????????confirm, ?????????????????????
     */
    public ConfirmAccountTxnResponse confirmTransaction(@Nonnull ConfirmAccountTxnRequest request, int retryTimesNow) {
        Objects.requireNonNull(request);
        // exit for fallback preventing infinity loop
        if (retryTimesNow > MAX_RETRY_CONFIRM_TIMES) {
            Shifts.fatal(StatusCode.FAIL_TO_CONFIRM);
        }
        final Long orderId = request.getOrderId();
        final Optional<AccountTransaction> nullableTxn = cancellableFindTransaction(orderId);
        if (!nullableTxn.isPresent()) {
            Shifts.fatal(StatusCode.ORDER_NOT_EXISTS);
        }
        ConfirmAccountTxnResponse response = Responses.generate(ConfirmAccountTxnResponse.class, StatusCode.OK);
        final AccountTransaction accountTransaction = nullableTxn.get();
        final ReservingState reservingState = accountTransaction.getState();
        if (reservingState == ReservingState.TRYING) {
            if (transactionMapper.compareAndSetState(accountTransaction.getId(), ReservingState.TRYING, ReservingState.CONFIRMED) <= 0) {
                // ATTENTION: u should force to retrieve from master node in production environment.
                return confirmTransaction(request, retryTimesNow + 1);
            }
        } else if (reservingState == ReservingState.CANCELLED) {
            response = Responses.generate(ConfirmAccountTxnResponse.class, StatusCode.TIMEOUT_AND_CANCELLED);
        } else if (reservingState == ReservingState.INVALID) {
            response = Responses.generate(ConfirmAccountTxnResponse.class, StatusCode.UNKNOWN_RESERVING_STATE);
        }
        return response;
    }

    public Optional<Account> findByUsername(@Nonnull String username) {
        Objects.requireNonNull(username);
        Preconditions.checkArgument(!username.isEmpty());
        return Optional.ofNullable(mapper.selectByName(username));
    }

}
