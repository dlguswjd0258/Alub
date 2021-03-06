package com.a105.alub.api.service;

import com.a105.alub.domain.entity.AssignedProblem;
import com.a105.alub.domain.entity.Study;
import com.a105.alub.domain.enums.Site;
import com.a105.alub.domain.repository.SolvedRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.a105.alub.api.request.CommitReq;
import com.a105.alub.api.request.ConfigsReq;
import com.a105.alub.api.request.FileGetReq;
import com.a105.alub.api.request.GitHubCommitReq;
import com.a105.alub.api.request.GithubTokenReq;
import com.a105.alub.api.request.LoginReq;
import com.a105.alub.api.request.RepoCreateReq;
import com.a105.alub.api.request.RepoSetReq;
import com.a105.alub.api.response.CommitRes;
import com.a105.alub.api.response.ConfigsRes;
import com.a105.alub.api.response.FileGetRes;
import com.a105.alub.api.response.GithubContentType;
import com.a105.alub.api.response.GithubFileContentRes;
import com.a105.alub.api.response.GithubRepo;
import com.a105.alub.api.response.GithubRepoContentRes;
import com.a105.alub.api.response.GithubRepoRes;
import com.a105.alub.api.response.GithubTokenRes;
import com.a105.alub.api.response.GithubUserRes;
import com.a105.alub.api.response.LoginRes;
import com.a105.alub.api.response.MyInfoRes;
import com.a105.alub.api.response.Readme;
import com.a105.alub.api.response.RepoContent;
import com.a105.alub.common.exception.AlreadyExistingRepoException;
import com.a105.alub.common.exception.DirSettingFailException;
import com.a105.alub.common.exception.FileNotFoundException;
import com.a105.alub.common.exception.RepoNotFoundException;
import com.a105.alub.common.exception.TimerFormatException;
import com.a105.alub.common.exception.TokenForbiddenException;
import com.a105.alub.common.exception.UserNotFoundException;
import com.a105.alub.config.GithubConfig;
import com.a105.alub.domain.entity.User;
import com.a105.alub.domain.enums.CommitType;
import com.a105.alub.domain.enums.Platform;
import com.a105.alub.domain.repository.UserRepository;
import com.a105.alub.security.GitHubAuthenticate;
import com.a105.alub.security.UserPrincipal;
import com.google.common.net.HttpHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private final GithubConfig githubConfig;
  private final UserRepository userRepository;
  private final SolvedRepository solvedRepository;
  private final GitHubAuthenticate gitHubAuthenticate;
  private final WebClient githubApiClient;

  /**
   * github ???????????? user ?????? ??? user ?????? ??????
   * 
   * @param loginReq github code??? platform??? ?????? ??????
   * @return user ????????? jwt ?????? ?????? ??????
   */
  @Override
  public LoginRes login(LoginReq loginReq) {

    GithubTokenRes githubTokenRes = getAccessToken(loginReq.getCode());
    log.info("Github Access Token Response: {}", githubTokenRes);

    GithubUserRes githubUserRes = getGithubUser(githubTokenRes.getAccessToken());

    User user = gitHubAuthenticate.checkUser(githubTokenRes, githubUserRes);

    UserDetails userDetails = loadUserByUsername(user.getName(), loginReq.getPlatform());

    String token = gitHubAuthenticate.getJwtToken(userDetails, loginReq.getPlatform());
    return LoginRes.builder().userId(user.getId()).name(user.getName()).email(user.getEmail())
        .imageUrl(user.getImageUrl()).token(token).build();
  }

  /**
   * ???????????? ????????? ????????? ??????
   *
   * @param userId ?????? ID
   * @return ??????????????? ?????? ?????? ??????
   */
  @Override
  public ConfigsRes getConfigs(Long userId) {

    User user = userRepository.findById(userId).orElseThrow(RuntimeException::new);

    return ConfigsRes.builder().commit(user.getCommit())
        .timerDefaultTime(user.getTimerDefaultTime()).timerShown(user.getTimerShown())
        .repoName(user.getRepoName()).dirPath(user.getDirPath()).build();
  }

  /**
   * ????????? update??????
   *
   * @param userId ?????? ID
   * @param configsReq ???????????? ???????????? ?????? ??????
   */
  @Override
  public void updateConfigs(Long userId, ConfigsReq configsReq) {
    User user = userRepository.findById(userId).orElseThrow(RuntimeException::new);

    // commit ??????
    if (configsReq.getCommit() != null) {
      user.setCommit(configsReq.getCommit());
    } else if (configsReq.getTimerDefaultTime() != null) { // ?????? ?????? ??????
      String time = checkTimeFormat(configsReq.getTimerDefaultTime());
      user.setTimerDefaultTime(time);
    } else { // ????????? ????????? ?????? ??????
      user.setTimerShown(configsReq.isTimerShown());
    }

    userRepository.save(user);
  }

  private String checkTimeFormat(String time) {
    final String REGEX = "([0-9]){2}(:[0-5][0-9]){2}";

    if (!time.matches(REGEX)) {
      throw new TimerFormatException("????????? ????????? ???????????? ????????????.");
    }

    return time;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    User user = userRepository.findByName(username)
        .orElseThrow(() -> new UsernameNotFoundException(username));
    return UserPrincipal.create(user);
  }

  @Override
  public UserDetails loadUserByUsername(String username, Platform platform)
      throws UsernameNotFoundException {
    User user = userRepository.findByName(username)
        .orElseThrow(() -> new UsernameNotFoundException(username));
    return UserPrincipal.create(user, platform);
  }

  /**
   * github??? user ?????? ????????????
   * 
   * @param githubAccessToken user ?????????????????? ?????? access_token
   * @return github ?????? ??????
   */
  private GithubUserRes getGithubUser(String githubAccessToken) {
    return githubApiClient.get().uri("/user")
        .header("Authorization", "token " + githubAccessToken).retrieve()
        .bodyToMono(GithubUserRes.class).block();
  }

  /**
   * code??? ?????? github access_token ????????????
   * 
   * @param code github?????? ???????????? ?????? code (?)
   * @return GithubTokenRes
   */
  private GithubTokenRes getAccessToken(String code) {
    GithubTokenReq githubTokenReq = GithubTokenReq.builder().code(code)
        .clientId(githubConfig.getClientId()).clientSecret(githubConfig.getClientSecret()).build();

    log.info("Github Access Token Request: {}", githubTokenReq);

    return githubApiClient.post().uri("https://github.com/login/oauth/access_token")
        .bodyValue(githubTokenReq).retrieve()
        .bodyToMono(GithubTokenRes.class).block();
  }

  /**
   * ????????? ????????????
   *
   * @param userId user ID
   * @return user ??????
   */
  @Override
  public MyInfoRes getMyInfo(Long userId) {
    User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException());
    return MyInfoRes.builder().userId(user.getId()).email(user.getEmail()).name(user.getName())
        .imageUrl(user.getImageUrl()).build();
  }

  /**
   * ?????? ?????? github??? ?????? public repo ????????? ??????
   * 
   * @param userId ?????? ID
   * @return
   */
  @Override
  public List<GithubRepoRes> getGithubPublicRepos(Long userId) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

    int resSize = 0;
    int page = 1;
    List<GithubRepo> githubRepos = new ArrayList<>();
    do {
      List<GithubRepo> resRepos = githubApiClient.get()
          .uri("/user/repos?visibility=public&affiliation=owner&per_page=100&page={page}", page++)
          .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken())
          .retrieve()
          .onStatus(status -> status == HttpStatus.NOT_FOUND,
              clientResponse -> clientResponse.createException()
                  .flatMap(it -> Mono.error(new RepoNotFoundException())))
          .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
              clientResponse -> clientResponse.bodyToMono(String.class)
                  .map(body -> new RuntimeException(body)))
          .bodyToFlux(GithubRepo.class)
          .collectList().blockOptional().orElse(new ArrayList<>());

      githubRepos.addAll(resRepos);
      resSize = resRepos.size();
    } while (resSize != 0);

    return githubRepos.stream()
        .filter(e -> !e.getFork())
        .map(GithubRepoRes::of)
        .collect(Collectors.toList());
  }

  /**
   * alub repo ??????
   * 
   * @param userId ?????? ID
   * @param repoSetReq repo ?????? ?????? request ?????????
   */
  @Override
  public void setAlubRepo(Long userId, RepoSetReq repoSetReq) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

    String repoName = repoSetReq.getRepoName();
    String dirPath = repoSetReq.getDirPath();

    if (repoSetReq.isCreation()) {
      createGithubPublicRepo(user, repoName);
      createReadmeInRepoDir(user, repoName, dirPath);
    } else {
      setExistingGithubRepoToAlubRepo(user, repoName, dirPath);
    }

    user.updateAlubRepo(repoName, dirPath);
    userRepository.save(user);
  }

  /**
   * github public repo ??????
   * 
   * @param user ??????
   * @param repoName ????????? repo ??????
   */
  private void createGithubPublicRepo(User user, String repoName) {
    RepoCreateReq repoCreateReq = new RepoCreateReq(repoName);
    GithubRepo createdRepo = githubApiClient.post()
        .uri("/user/repos")
        .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(repoCreateReq)
        .retrieve()
        .onStatus(status -> status == HttpStatus.UNPROCESSABLE_ENTITY,
            clientResponse -> clientResponse.bodyToMono(String.class)
                .map(body -> new AlreadyExistingRepoException()))
        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
            clientResponse -> clientResponse.bodyToMono(String.class)
                .map(body -> new RuntimeException(body)))
        .bodyToMono(GithubRepo.class)
        .block();
  }

  /**
   * ?????? ????????? ????????? ?????? github repo ??????
   * 
   * @param userId ?????? ID
   * @param repoName ????????? repo ??????
   */
  @Override
  public GithubRepoRes getGithubRepo(Long userId, String repoName) {
    User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

    int resSize = 0;
    int page = 1;
    List<GithubRepo> githubAllRepos = new ArrayList<>();
    do {
      List<GithubRepo> reposPerPage = githubApiClient.get()
          .uri("/user/repos?affiliation=owner&per_page=100&page={page}", page++)
          .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken())
          .retrieve()
          .onStatus(status -> status == HttpStatus.NOT_FOUND,
              clientResponse -> clientResponse.createException()
                  .flatMap(it -> Mono.error(new RepoNotFoundException())))
          .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
              clientResponse -> clientResponse.bodyToMono(String.class)
                  .map(body -> new RuntimeException(body)))
          .bodyToFlux(GithubRepo.class)
          .collectList().blockOptional().orElse(new ArrayList<>());

      githubAllRepos.addAll(reposPerPage);
      resSize = reposPerPage.size();
    } while (resSize != 0);

    githubAllRepos = githubAllRepos.stream()
        .filter(githubRepo -> githubRepo.getName().equalsIgnoreCase(repoName))
        .collect(Collectors.toList());

    int numOfGithubRepo = githubAllRepos.size();
    if (numOfGithubRepo > 1) {
      throw new IllegalStateException();
    } else if (numOfGithubRepo == 0) {
      throw new RepoNotFoundException();
    }

    return GithubRepoRes.of(githubAllRepos.get(0));
  }

  /**
   * ?????? ???????????? github repo??? alub repo??? ??????
   * 
   * @param user ??????
   * @param repoName alub repo??? ????????? ?????? ???????????? github repo ??????
   * @param dirPath alub repo??? ????????? directory ??????
   */
  private void setExistingGithubRepoToAlubRepo(User user, String repoName, String dirPath) {
    List<RepoContent> repoContents = getRepoContents(user, repoName, dirPath);
    if (repoContents.size() <= 0) {
      // dirPath/README.md ??????
      createReadmeInRepoDir(user, repoName, dirPath);
    } else {
      RepoContent firstContent = repoContents.get(0);
      GithubContentType githubContentType = firstContent.getType();
      String contentFullName = firstContent.getPath();

      if (!contentFullName.equals(dirPath)) {
        if (!getReadmeInRepoDir(user, repoName, dirPath).isPresent()) {
          createReadmeInRepoDir(user, repoName, dirPath);
        }
      } else {
        // repo ?????? ??????
        throw new DirSettingFailException();
      }
    }
  }

  /**
   * github repo??? ?????? ????????? README.md ??????
   * 
   * @param user ??????
   * @param repoName README.md ????????? repo ??????
   * @param dirPath README.md ????????? ??????
   * @return
   */
  public boolean createReadmeInRepoDir(User user, String repoName, String dirPath) {
    String readmeContent = "# Alub \r\n??? ????????? ?????? ?????? ?????? ?????? ?????????";
    Readme readme = new Readme("Create README.md by Alub", readmeContent);

    StringBuilder uri = new StringBuilder("/repos/{userName}/{repoName}/contents");
    Map<String, String> uriPathVariables = new HashMap<>();
    uriPathVariables.put("userName", user.getName());
    uriPathVariables.put("repoName", repoName);
    if (dirPath != null && !dirPath.equals("")) {
      uri.append("/{dirPath}");
      uriPathVariables.put("dirPath", dirPath);
    }
    uri.append("/README.md");

    RepoContent repoContents = githubApiClient.put()
        .uri(uri.toString(), uriPathVariables)
        .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken())
        .bodyValue(readme)
        .retrieve()
        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
            clientResponse -> clientResponse.bodyToMono(String.class)
                .map(body -> new RuntimeException(body)))
        .bodyToMono(RepoContent.class).block();

    return true;
  }

  /**
   * ?????? github repo??? contents ?????? ??????
   * 
   * @param user ??????
   * @param repoName contents??? ????????? repo ??????
   * @param dirPath contents??? ????????? ??????
   * @return
   */
  private List<RepoContent> getRepoContents(User user, String repoName, String dirPath) {

    StringBuilder uri = new StringBuilder("/repos/{userName}/{repoName}/contents");
    Map<String, String> uriPathVariables = new HashMap<>();
    uriPathVariables.put("userName", user.getName());
    uriPathVariables.put("repoName", repoName);
    if (dirPath != null && !dirPath.equals("")) {
      uri.append("/{dirPath}");
      uriPathVariables.put("dirPath", dirPath);
    }

    List<RepoContent> repoContents = githubApiClient.get()
        .uri(uri.toString(), uriPathVariables)
        .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken())
        .retrieve()
        .onStatus(status -> (status.is4xxClientError() && status != HttpStatus.NOT_FOUND)
                || status.is5xxServerError(),
            clientResponse -> clientResponse.bodyToMono(String.class)
                .map(body -> new RuntimeException(body)))
        .bodyToFlux(RepoContent.class)
        .onErrorResume(WebClientResponseException.NotFound.class, notFound -> Mono.empty())
        .collectList().blockOptional().orElse(new ArrayList<>());

    return repoContents;
  }

  /**
   * github repo??? ?????? ????????? README.md ??????
   * 
   * @param user ??????
   * @param repoName README.md??? ????????? repo ??????
   * @param dirPath README.md??? ????????? ??????
   * @return
   */
  private Optional<RepoContent> getReadmeInRepoDir(User user, String repoName, String dirPath) {
    Optional<RepoContent> readme = githubApiClient.get()
        .uri("/repos/{userName}/{repoName}/readme/{dirPath}",
            user.getName(), repoName, dirPath)
        .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken())
        .retrieve()
        .onStatus(status -> (status.is4xxClientError() && status != HttpStatus.NOT_FOUND)
                || status.is5xxServerError(),
            clientResponse -> clientResponse.bodyToMono(String.class)
                .map(body -> new RuntimeException(body)))
        .bodyToMono(RepoContent.class)
        .onErrorResume(WebClientResponseException.NotFound.class, notFound -> Mono.empty())
        .blockOptional();
    return readme;
  }

  /**
   * github repo ????????? ?????? ?????? ??????
   * 
   * @param id ????????? user??? id
   * @param fileGetReq Get??? FilePath?????? ?????? ??????
   * @return
   */
  @Override
  public FileGetRes getFile(Long id, FileGetReq fileGetReq) {
    User user = userRepository.findById(id).orElseThrow(UserNotFoundException::new);
    StringBuilder uri = new StringBuilder("/repos/{userName}/{repoName}/contents");

    Map<String, String> uriPathVariables = new HashMap<>();
    uriPathVariables.put("userName", user.getName());
    uriPathVariables.put("repoName", user.getRepoName());
    String dirPath = user.getDirPath() == null ? "" : user.getDirPath();
    if (!dirPath.equals("")) {
      uri.append("/{dirPath}");
      uriPathVariables.put("dirPath", dirPath);
    }
    uri.append("/{site}");
    uriPathVariables.put("site", fileGetReq.getSite().toString());
    uri.append("/{problemNum}");
    uriPathVariables.put("problemNum", fileGetReq.getProblemNum());
    uri.append("/{fileName}");
    uriPathVariables.put("fileName", fileGetReq.getFileName());

    try {
      GithubFileContentRes githubFileContentRes = getFile(user, uri, uriPathVariables);
      FileGetRes fileGetRes = new FileGetRes(githubFileContentRes);
      log.info("Get File Contes: {}", fileGetRes);

      return fileGetRes;
    } catch (Exception e) {
      throw new FileNotFoundException();
    }

  }

  /**
   * github repo??? ????????? ????????? file commit
   * 
   * @param id ????????? user??? id
   * @param commitReq Post??? reqBody
   * @return
   */
  @Override
  public CommitRes commit(Long id, CommitReq commitReq) {
    User user = userRepository.findById(id).orElseThrow(UserNotFoundException::new);
    StringBuilder uri = new StringBuilder("/repos/{userName}/{repoName}/contents");

    Map<String, String> uriPathVariables = new HashMap<>();
    uriPathVariables.put("userName", user.getName());
    uriPathVariables.put("repoName", user.getRepoName());
    String dirPath = user.getDirPath() == null ? "" : user.getDirPath();
    if (!dirPath.equals("")) {
      uri.append("/{dirPath}");
      uriPathVariables.put("dirPath", dirPath);
    }
    uri.append("/{site}");
    uriPathVariables.put("site", commitReq.getSite().toString());
    uri.append("/{problemNum}");
    uriPathVariables.put("problemNum", commitReq.getProblemNum());

    CommitRes commitRes;
    if (commitReq.getCommit() == CommitType.DEFAULT) {
      commitRes = defaultCommit(user, commitReq, uri, uriPathVariables);
    } else {
      commitRes = customCommit(user, commitReq, uri, uriPathVariables);
    }

    updateSolved(user.getId(), commitReq.getSite(), Long.parseLong(commitReq.getProblemNum()));

    return commitRes;
  }

  private void updateSolved(Long userId, Site site, Long problemNum) {
    LocalDateTime now = LocalDateTime.now();
    solvedRepository.findByUser_IdAndSolvedIsFalse(userId)
        .stream()
        .filter(s -> {
          AssignedProblem assignedProblem = s.getAssignedProblem();
          Study study = assignedProblem.getStudy();
          return assignedProblem.getNum().equals(problemNum)
              && assignedProblem.getSite() == site
              && study.getAssignmentEndTime().isAfter(now)
              && study.getAssignmentStartTime().isBefore(now);
        })
        .forEach(s -> {
          s.solveAssignedProblem(now);
          solvedRepository.save(s);
        });
  }

  /**
   * github repo??? ????????? ????????? default commit
   * 
   * @param user ????????? user
   * @param commitReq Post??? reqBody
   * @param url github ????????? ?????? url
   * @return commit ????????? path
   */
  private CommitRes defaultCommit(User user, CommitReq commitReq, StringBuilder uri,
      Map<String, String> uriPathVariables) {

    Long cnt =
        getCommitCnt(uri, uriPathVariables, user.getGithubAccessToken(), commitReq.getProblemNum());
    String fileName = commitReq.getProblemNum() + "_" + cnt + "." + commitReq.getLanguage();
    uri.append("/{fileName}");
    uriPathVariables.put("fileName", fileName);

    GitHubCommitReq gitHubCommitReq = new GitHubCommitReq(commitReq, commitReq.getLanguage());
    log.info(gitHubCommitReq.toString());

    try {

      githubApiClient.put().uri(uri.toString(), uriPathVariables).bodyValue(gitHubCommitReq)
          .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken()).retrieve()
          .onStatus(status -> status == HttpStatus.NOT_FOUND,
              clientResponse -> clientResponse.createException()
                  .flatMap(it -> Mono.error(new RepoNotFoundException())))
          .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
              clientResponse -> clientResponse.bodyToMono(String.class)
                  .map(body -> new RuntimeException(body)))
          .toEntity(String.class).block();

      log.info("Sucess Commit to: {}", getUrl(uriPathVariables));
      return new CommitRes(uriPathVariables);
    } catch (Exception e) {
      log.info("Fail Commit to: {}", getUrl(uriPathVariables));
      throw e;
    }
  }

  /**
   * github repo??? ????????? ????????? custom commit ????????? ????????? ????????????
   * 
   * @param user ????????? user
   * @param commitReq Post??? reqBody
   * @param url github ????????? ?????? url
   * @return commit ????????? path
   */
  private CommitRes customCommit(User user, CommitReq commitReq, StringBuilder uri,
      Map<String, String> uriPathVariables) {
    String fileName = commitReq.getFileName() + "." + commitReq.getLanguage();
    uri.append("/{fileName}");
    uriPathVariables.put("fileName", fileName);

    GithubFileContentRes githubFileContentRes = getFile(user, uri, uriPathVariables);
    if (githubFileContentRes != null) {
      commitReq.setSha(githubFileContentRes.getSha());
    }

    GitHubCommitReq gitHubCommitReq = new GitHubCommitReq(commitReq, commitReq.getLanguage());
    log.info(gitHubCommitReq.toString());

    try {
      githubApiClient.put().uri(uri.toString(), uriPathVariables).bodyValue(gitHubCommitReq)
          .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken()).retrieve()
          .onStatus(status -> status == HttpStatus.NOT_FOUND,
              clientResponse -> clientResponse.createException()
                  .flatMap(it -> Mono.error(new RepoNotFoundException())))
          .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
              clientResponse -> clientResponse.bodyToMono(String.class)
                  .map(body -> new RuntimeException(body)))
          .toEntity(String.class).block();

      log.info("Sucess Commit to: {}", getUrl(uriPathVariables));
      return new CommitRes(uriPathVariables);
    } catch (Exception e) {
      log.info("Fail Commit to: {}", getUrl(uriPathVariables));
      throw e;
    }
  }

  /**
   * github repo ????????? ?????? ?????? ??????
   *
   * @param user ????????? ??????
   * @param url github api ????????? ?????? ??????
   * @return ?????? ??????
   */
  private GithubFileContentRes getFile(User user, StringBuilder uri,
      Map<String, String> uriPathVariables) {
    try {
      GithubFileContentRes response = githubApiClient.get().uri(uri.toString(), uriPathVariables)
          .header(HttpHeaders.AUTHORIZATION, "token " + user.getGithubAccessToken()).retrieve()
          .onStatus(status -> status == HttpStatus.NOT_FOUND,
              clientResponse -> clientResponse.createException()
                  .flatMap(it -> Mono.error(new FileNotFoundException())))
          .onStatus(status -> status == HttpStatus.FORBIDDEN,
              clientResponse -> clientResponse.createException()
                  .flatMap(it -> Mono.error(new TokenForbiddenException())))
          .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
              clientResponse -> clientResponse.bodyToMono(String.class)
                  .map(body -> new RuntimeException(body)))
          .bodyToMono(GithubFileContentRes.class).block();
      log.info("Sucess Get File from : {}", getUrl(uriPathVariables));
      return response;
    } catch (Exception e) {
      log.info("Fail Get File from: {}", getUrl(uriPathVariables));
      return null;
    }
  }

  /**
   * code??? ?????? github repo?????? ????????? ????????? ????????????
   *
   * @param url github api ????????? ?????? ??????
   * @param token ????????? github token
   * @param problemNum ?????? ????????? ?????? ?????? ??????
   * @return ?????????+1
   */
  private Long getCommitCnt(StringBuilder uri, Map<String, String> uriPathVariables, String token,
      String problemNum) {
    Long cnt = 1L;
    try {

      List<GithubRepoContentRes> response =
          githubApiClient.get().uri(uri.toString(), uriPathVariables)
              .header(HttpHeaders.AUTHORIZATION, "token " + token).retrieve()
              .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                  clientResponse -> clientResponse.bodyToMono(String.class)
                      .map(body -> new RuntimeException(body)))
              .bodyToMono(new ParameterizedTypeReference<List<GithubRepoContentRes>>() {}).block();

      Pattern pattern = Pattern.compile("^" + problemNum + "_\\d+\\b");
      String maxFileName = response.stream().filter(entry -> {
        Matcher matcher = pattern.matcher(entry.getName());
        return matcher.find();
      }).max((entry1, entry2) -> {
        Matcher matcher1 = pattern.matcher(entry1.getName());
        Matcher matcher2 = pattern.matcher(entry2.getName());
        matcher1.find();
        matcher2.find();
        StringTokenizer st1 = new StringTokenizer(matcher1.group(), "_");
        StringTokenizer st2 = new StringTokenizer(matcher2.group(), "_");
        st1.nextToken();
        st2.nextToken();
        return Long.parseLong(st1.nextToken()) > Long.parseLong(st2.nextToken()) ? 1 : -1;
      }).get().getName();

      Matcher matcher = pattern.matcher(maxFileName);
      matcher.find();
      StringTokenizer st = new StringTokenizer(matcher.group(), "_");
      st.nextToken();
      cnt = Long.parseLong(st.nextToken()) + 1L;
    } catch (Exception e) {
      // ????????? ?????? ?????? ??? ?????? ???????????? ????????? ???????????? ?????? ?????? 1??? return
    }
    return cnt;
  }

  /**
   * ????????? github url ????????????
   *
   * @param uriPathVariables url ????????? ???????????? Map
   * @return ????????? url
   */
  private String getUrl(Map<String, String> uriPathVariables) {
    StringBuilder uri = new StringBuilder();
    uri.append(uriPathVariables.get("userName"));
    String dirPath = uriPathVariables.getOrDefault("dirPath", "");
    if (!dirPath.equals("")) {
      uri.append("/" + dirPath);
    }
    uri.append("/" + uriPathVariables.get("repoName"));
    uri.append("/" + uriPathVariables.get("site"));
    uri.append("/" + uriPathVariables.get("problemNum"));
    uri.append("/" + uriPathVariables.get("fileName"));
    return uri.toString();
  }
}
