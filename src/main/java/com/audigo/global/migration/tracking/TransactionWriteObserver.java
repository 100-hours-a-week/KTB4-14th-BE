package com.audigo.global.migration.tracking;

import org.springframework.transaction.TransactionDefinition;

// 트랜잭션 매니저가 쓰기 트랜잭션의 시작과 종료를 알리는 대상
public interface TransactionWriteObserver {

    // readOnly가 아닌 트랜잭션이 실제로 시작되기 직전
    void beforeWriteBegin(Object transaction, TransactionDefinition definition);

    // 트랜잭션의 커밋·롤백·정리가 끝난 뒤. 시작에 실패한 경우에도 호출된다
    void afterCompletion(Object transaction);
}
