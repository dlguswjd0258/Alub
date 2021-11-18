package com.a105.alub.api.response;

import com.a105.alub.domain.entity.Solved;
import com.a105.alub.domain.entity.User;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class SolvedGetRes {

  private final Long userId;

  private final String userName;

  private final boolean solved;

  private final LocalDateTime solvedTime;

  public SolvedGetRes(User user, Solved solved) {
    this.userId = user.getId();
    this.userName = user.getName();
    this.solved = solved.isSolved();
    this.solvedTime = solved.getSolvedTime();
  }

}
