/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.project.ws;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.Paging;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentQuery;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.permission.OrganizationPermission;
import org.sonar.server.project.Visibility;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.WsProjects.SearchWsResponse;
import org.sonarqube.ws.client.project.SearchWsRequest;

import static com.google.common.base.Preconditions.checkArgument;
import static org.sonar.api.resources.Qualifiers.APP;
import static org.sonar.api.resources.Qualifiers.PROJECT;
import static org.sonar.api.resources.Qualifiers.VIEW;
import static org.sonar.api.utils.DateUtils.formatDateTime;
import static org.sonar.api.utils.DateUtils.parseDateOrDateTime;
import static org.sonar.core.util.Protobuf.setNullable;
import static org.sonar.core.util.Uuids.UUID_EXAMPLE_01;
import static org.sonar.core.util.Uuids.UUID_EXAMPLE_02;
import static org.sonar.server.project.Visibility.PRIVATE;
import static org.sonar.server.project.Visibility.PUBLIC;
import static org.sonar.server.ws.KeyExamples.KEY_PROJECT_EXAMPLE_001;
import static org.sonar.server.ws.KeyExamples.KEY_PROJECT_EXAMPLE_002;
import static org.sonar.server.ws.WsUtils.writeProtobuf;
import static org.sonarqube.ws.WsProjects.SearchWsResponse.Component;
import static org.sonarqube.ws.WsProjects.SearchWsResponse.newBuilder;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.ACTION_SEARCH;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.MAX_PAGE_SIZE;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_ANALYZED_BEFORE;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_ON_PROVISIONED_ONLY;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_ORGANIZATION;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_PROJECTS;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_PROJECT_IDS;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_QUALIFIERS;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_VISIBILITY;

public class SearchAction implements ProjectsWsAction {

  private final DbClient dbClient;
  private final UserSession userSession;
  private final ProjectsWsSupport support;

  public SearchAction(DbClient dbClient, UserSession userSession, ProjectsWsSupport support) {
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.support = support;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction(ACTION_SEARCH)
      .setSince("6.3")
      .setDescription("Search for projects or views to administrate them.<br>" +
        "Requires 'System Administrator' permission")
      .addPagingParams(100, MAX_PAGE_SIZE)
      .setResponseExample(getClass().getResource("search-example.json"))
      .setHandler(this);

    action.setChangelog(new Change("6.4", "The 'uuid' field is deprecated in the response"));

    action.createParam(Param.TEXT_QUERY)
      .setDescription("Limit search to: <ul>" +
        "<li>component names that contain the supplied string</li>" +
        "<li>component keys that contain the supplied string</li>" +
        "</ul>")
      .setExampleValue("sonar");

    action.createParam(PARAM_QUALIFIERS)
      .setDescription("Comma-separated list of component qualifiers. Filter the results with the specified qualifiers")
      .setPossibleValues(PROJECT, VIEW, APP)
      .setDefaultValue(PROJECT);
    support.addOrganizationParam(action);

    action.createParam(PARAM_VISIBILITY)
      .setDescription("Filter the projects that should be visible to everyone (%s), or only specific user/groups (%s).<br/>" +
        "If no visibility is specified, the default project visibility of the organization will be used.",
        Visibility.PUBLIC.getLabel(), Visibility.PRIVATE.getLabel())
      .setRequired(false)
      .setInternal(true)
      .setSince("6.4")
      .setPossibleValues(Visibility.getLabels());

    action.createParam(PARAM_ANALYZED_BEFORE)
      .setDescription("Filter the projects for which last analysis is older than the given date (exclusive).<br> " +
        "Format: date or datetime ISO formats.")
      .setSince("6.6");

    action.createParam(PARAM_ON_PROVISIONED_ONLY)
      .setDescription("Filter the projects that are provisioned")
      .setBooleanPossibleValues()
      .setDefaultValue("false")
      .setSince("6.6");

    action
      .createParam(PARAM_PROJECTS)
      .setDescription("Comma-separated list of project keys")
      .setSince("6.6")
      .setExampleValue(String.join(",", KEY_PROJECT_EXAMPLE_001, KEY_PROJECT_EXAMPLE_002));

    action
      .createParam(PARAM_PROJECT_IDS)
      .setDescription("Comma-separated list of project ids")
      .setSince("6.6")
      // parameter added to match api/projects/bulk_delete parameters
      .setDeprecatedSince("6.6")
      .setExampleValue(String.join(",", UUID_EXAMPLE_01, UUID_EXAMPLE_02));
  }

  @Override
  public void handle(Request wsRequest, Response wsResponse) throws Exception {
    SearchWsResponse searchWsResponse = doHandle(toSearchWsRequest(wsRequest));
    writeProtobuf(searchWsResponse, wsRequest, wsResponse);
  }

  private static SearchWsRequest toSearchWsRequest(Request request) {
    return SearchWsRequest.builder()
      .setOrganization(request.param(PARAM_ORGANIZATION))
      .setQualifiers(request.mandatoryParamAsStrings(PARAM_QUALIFIERS))
      .setQuery(request.param(Param.TEXT_QUERY))
      .setPage(request.mandatoryParamAsInt(Param.PAGE))
      .setPageSize(request.mandatoryParamAsInt(Param.PAGE_SIZE))
      .setVisibility(request.param(PARAM_VISIBILITY))
      .setAnalyzedBefore(request.param(PARAM_ANALYZED_BEFORE))
      .setOnProvisionedOnly(request.mandatoryParamAsBoolean(PARAM_ON_PROVISIONED_ONLY))
      .setProjects(request.paramAsStrings(PARAM_PROJECTS))
      .setProjectIds(request.paramAsStrings(PARAM_PROJECT_IDS))
      .build();
  }

  private SearchWsResponse doHandle(SearchWsRequest request) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      OrganizationDto organization = support.getOrganization(dbSession, request.getOrganization());
      userSession.checkPermission(OrganizationPermission.ADMINISTER, organization);

      ComponentQuery query = buildDbQuery(request);
      Paging paging = buildPaging(dbSession, request, organization, query);
      List<ComponentDto> components = dbClient.componentDao().selectByQuery(dbSession, organization.getUuid(), query, paging.offset(), paging.pageSize());
      Map<String, Long> analysisDateByComponentUuid = dbClient.snapshotDao()
        .selectLastAnalysesByRootComponentUuids(dbSession, components.stream().map(ComponentDto::uuid).collect(MoreCollectors.toList())).stream()
        .collect(MoreCollectors.uniqueIndex(SnapshotDto::getComponentUuid, SnapshotDto::getCreatedAt));
      return buildResponse(components, organization, analysisDateByComponentUuid, paging);
    }
  }

  private static ComponentQuery buildDbQuery(SearchWsRequest request) {
    List<String> qualifiers = request.getQualifiers();
    ComponentQuery.Builder query = ComponentQuery.builder()
      .setQualifiers(qualifiers.toArray(new String[qualifiers.size()]));

    setNullable(request.getQuery(), q -> {
      query.setNameOrKeyQuery(q);
      query.setPartialMatchOnKey(true);
      return query;
    });
    setNullable(request.getVisibility(), v -> query.setPrivate(Visibility.isPrivate(v)));
    setNullable(request.getAnalyzedBefore(), d -> query.setAnalyzedBefore(parseDateOrDateTime(d).getTime()));
    setNullable(request.isOnProvisionedOnly(), query::setOnProvisionedOnly);
    setNullable(request.getProjects(), keys -> query.setComponentKeys(new HashSet<>(keys)));
    setNullable(request.getProjectIds(), uuids -> query.setComponentUuids(new HashSet<>(uuids)));

    return query.build();
  }

  private Paging buildPaging(DbSession dbSession, SearchWsRequest request, OrganizationDto organization, ComponentQuery query) {
    int total = dbClient.componentDao().countByQuery(dbSession, organization.getUuid(), query);
    return Paging.forPageIndex(request.getPage())
      .withPageSize(request.getPageSize())
      .andTotal(total);
  }

  private static SearchWsResponse buildResponse(List<ComponentDto> components, OrganizationDto organization, Map<String, Long> analysisDateByComponentUuid, Paging paging) {
    SearchWsResponse.Builder responseBuilder = newBuilder();
    responseBuilder.getPagingBuilder()
      .setPageIndex(paging.pageIndex())
      .setPageSize(paging.pageSize())
      .setTotal(paging.total())
      .build();

    components.stream()
      .map(dto -> dtoToProject(organization, dto, analysisDateByComponentUuid.get(dto.uuid())))
      .forEach(responseBuilder::addComponents);
    return responseBuilder.build();
  }

  private static Component dtoToProject(OrganizationDto organization, ComponentDto dto, @Nullable Long analysisDate) {
    checkArgument(
      organization.getUuid().equals(dto.getOrganizationUuid()),
      "No Organization found for uuid '%s'",
      dto.getOrganizationUuid());

    Component.Builder builder = Component.newBuilder()
      .setOrganization(organization.getKey())
      .setId(dto.uuid())
      .setKey(dto.getDbKey())
      .setName(dto.name())
      .setQualifier(dto.qualifier())
      .setVisibility(dto.isPrivate() ? PRIVATE.getLabel() : PUBLIC.getLabel());
    setNullable(analysisDate, d -> builder.setLastAnalysisDate(formatDateTime(d)));

    return builder.build();
  }

}
