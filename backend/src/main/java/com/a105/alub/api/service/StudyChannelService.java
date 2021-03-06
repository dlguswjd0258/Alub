package com.a105.alub.api.service;

import java.util.List;
import com.a105.alub.api.request.StudyChannelCreateReq;
import com.a105.alub.api.request.StudyChannelModifyReq;
import com.a105.alub.api.response.StudyChannelCreateRes;
import com.a105.alub.api.response.StudyChannelDto;
import com.a105.alub.api.response.StudyChannelMemberDto;
import com.a105.alub.api.response.StudyChannelRes;

public interface StudyChannelService {

  StudyChannelCreateRes createChannel(Long userId, StudyChannelCreateReq channelCreateReq);

  StudyChannelRes getChannel(Long channelId);

  void modifyChannel(Long userId, Long channelId, StudyChannelModifyReq channelModifyReq);

  void deleteChannel(Long userId, Long channelId);

  List<StudyChannelDto> getChannelList(Long userId);

  List<StudyChannelMemberDto> getMemberList(Long channelId);
}
