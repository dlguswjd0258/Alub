package com.a105.alub.api.controller;

import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.a105.alub.api.request.CommitReq;
import com.a105.alub.api.request.ConfigsReq;
import com.a105.alub.api.request.FileGetReq;
import com.a105.alub.api.request.LoginReq;
import com.a105.alub.api.request.RepoSetReq;
import com.a105.alub.api.response.CommitRes;
import com.a105.alub.api.response.ConfigsRes;
import com.a105.alub.api.response.FileGetRes;
import com.a105.alub.api.response.GithubRepoRes;
import com.a105.alub.api.response.LoginRes;
import com.a105.alub.api.response.MyInfoRes;
import com.a105.alub.api.service.UserService;
import com.a105.alub.common.exception.WrongRepoParamException;
import com.a105.alub.common.response.ApiResponseDto;
import com.a105.alub.domain.enums.Site;
import com.a105.alub.security.CurrentUser;
import com.a105.alub.security.UserPrincipal;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import springfox.documentation.annotations.ApiIgnore;

@Slf4j
@RequestMapping("/api/user")
@RestController
@RequiredArgsConstructor
public class UserController {
  private static final Pattern DIR_PATH_ANTI_PATTERN = Pattern.compile("//+");
  private final UserService userService;

  @ApiOperation(value = "github authenticate", notes = "github 사용자 인증을 합니다.",
      response = ApiResponseDto.class)
  @ApiResponses({@ApiResponse(code = 200, message = "성공"),
      @ApiResponse(code = 401, message = "인증 실패"), @ApiResponse(code = 404, message = "페이지 없음"),
      @ApiResponse(code = 500, message = "서버 오류")})
  @PostMapping("/authenticate")
  public ApiResponseDto<LoginRes> login(@RequestBody LoginReq loginReq) {

    LoginRes loginResponse = userService.login(loginReq);
    log.info("Login Response: {}", loginResponse);

    return ApiResponseDto.success(loginResponse);
  }

  @GetMapping("/")
  public ApiResponseDto<MyInfoRes> getMyInfo(@ApiIgnore @CurrentUser UserPrincipal userPrincipal) {
    MyInfoRes userInfoRes = userService.getMyInfo(userPrincipal.getId());
    log.info("Get User Info: {}", userInfoRes);

    return ApiResponseDto.success(userInfoRes);
  }

  @ApiOperation(value = "사용자 설정 정보", notes = "인증된 사용자의 설정 정보를 반환합니다.",
      response = ApiResponseDto.class)
  @ApiResponses({@ApiResponse(code = 200, message = "성공"),
      @ApiResponse(code = 401, message = "인증 실패"), @ApiResponse(code = 403, message = "인가 실패"),
      @ApiResponse(code = 500, message = "서버 오류")})
  @GetMapping("/configs")
  public ApiResponseDto<ConfigsRes> getConfigs(
      @ApiIgnore @CurrentUser UserPrincipal userPrincipal) {

    ConfigsRes configsRes = userService.getConfigs(userPrincipal.getId());
    log.info("Get User Configuration: {}", configsRes);

    return ApiResponseDto.success(configsRes);
  }

  @ApiOperation(value = "사용자 commit, timer 설정", notes = "인증된 사용자의 commit, timer 설정합니다.",
      response = ApiResponseDto.class)
  @ApiResponses({@ApiResponse(code = 200, message = "성공"),
      @ApiResponse(code = 401, message = "인증 실패"), @ApiResponse(code = 403, message = "인가 실패"),
      @ApiResponse(code = 500, message = "서버 오류")})
  @PatchMapping("/configs")
  public ApiResponseDto<String> updateConfigs(@ApiIgnore @CurrentUser UserPrincipal userPrincipal,
      @RequestBody ConfigsReq configsReq) {

    userService.updateConfigs(userPrincipal.getId(), configsReq);
    log.info("Success setting user configs");

    return ApiResponseDto.DEFAULT_SUCCESS;
  }

  @GetMapping("/repos")
  public ApiResponseDto<List<GithubRepoRes>> getGithubPublicRepos(
      @ApiIgnore @CurrentUser UserPrincipal userPrincipal) {

    List<GithubRepoRes> githubRepoResList = userService.getGithubPublicRepos(userPrincipal.getId());
    log.info("GET /repos - response : {}, userId : {}", githubRepoResList.toArray(),
        userPrincipal.getId());
    return ApiResponseDto.success(githubRepoResList);
  }

  @PutMapping("/repos")
  public ApiResponseDto<String> setAlubRepo(@RequestBody RepoSetReq repoSetReq,
      @ApiIgnore @CurrentUser UserPrincipal userPrincipal) {
    log.info("PUT /repos - request : {}, userId : {}", repoSetReq, userPrincipal.getId());
    repoSetReq.setDirPath(repoSetReq.getDirPath().trim());
    if (!matchesRepoNamePattern(repoSetReq.getRepoName())
        || !matchesDirPathPattern(repoSetReq.getDirPath())) {
      throw new WrongRepoParamException();
    }

    userService.setAlubRepo(userPrincipal.getId(), repoSetReq);
    return ApiResponseDto.DEFAULT_SUCCESS;
  }

  @GetMapping("/repos/{repoName}")
  public ApiResponseDto<GithubRepoRes> getGithubRepo(@PathVariable String repoName,
      @ApiIgnore @CurrentUser UserPrincipal userPrincipal) {
    log.info("GET /repos/{} - userId : {}", repoName, userPrincipal.getId());
    if (!matchesRepoNamePattern(repoName)) {
      throw new WrongRepoParamException();
    }

    GithubRepoRes githubRepoRes = userService.getGithubRepo(userPrincipal.getId(), repoName);
    log.info("GET /repos/{} - userId : {}, response : {}", repoName, userPrincipal.getId(),
        githubRepoRes);
    return ApiResponseDto.success(githubRepoRes);
  }

  @GetMapping("/sites/{siteName}/problems/{problemNum}/files/{fileName}")
  public ApiResponseDto<FileGetRes> getGithubFile(
      @ApiIgnore @CurrentUser UserPrincipal userPrincipal, @PathVariable Site siteName,
      @PathVariable String problemNum, @PathVariable String fileName) throws IOException {
    FileGetReq fileGetReq =
        FileGetReq.builder().site(siteName).fileName(fileName).problemNum(problemNum).build();
    FileGetRes fileGetRes = userService.getFile(userPrincipal.getId(), fileGetReq);

    return ApiResponseDto.success(fileGetRes);
  }

  @PostMapping("/commits")
  public ApiResponseDto<CommitRes> commit(@ApiIgnore @CurrentUser UserPrincipal userPrincipal,
      @RequestBody CommitReq commitReq) throws IOException {

    CommitRes commitRes = userService.commit(userPrincipal.getId(), commitReq);

    return ApiResponseDto.success(commitRes);
  }

  private boolean matchesRepoNamePattern(String repoName) {
    return repoName.matches("([A-Za-z0-9-_.]+)");
  }

  private boolean matchesDirPathPattern(String dirPath) {
    return !dirPath.startsWith("/") && !dirPath.endsWith("/")
        && !DIR_PATH_ANTI_PATTERN.matcher(dirPath).find();
  }
}
