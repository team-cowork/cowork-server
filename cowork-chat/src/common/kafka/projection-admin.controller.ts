import {
    Body,
    Controller,
    Get,
    Param,
    Post,
} from '@nestjs/common';
import {ApiHeader, ApiOperation, ApiTags} from '@nestjs/swagger';
import {UserRole} from '../enum/user-role.enum';
import {Roles} from '../guard/roles.decorator';
import {RequestProjectionRebuildDto} from './dto/request-projection-rebuild.dto';
import {ProjectionReadinessService} from './projection-readiness.service';

/**
 전역 prefix가 붙어 서버 직접 호출 경로는 `/chat/admin/projections`이고, Gateway 경유 경로는 `/api/chat/chat/admin/projections`이다.
 `main.ts`가 `/chat/admin/` 경로를 projection readiness 검사에서 제외하므로 `REBUILD_REQUIRED`로 준비되지 않은 상태에서도 호출할 수 있다.
 서버에 직접 호출하면 `X-User-Role: ADMIN`과 양의 정수 `X-User-Id`를 함께 보내야 한다.
 */
@ApiTags('Projection Admin')
@ApiHeader({name: 'X-User-Id', description: 'Gateway 자동 주입 (서비스 직접 호출 시 양의 안전 정수 필수)', required: false})
@ApiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN)', required: true})
@Roles(UserRole.ADMIN)
@Controller('admin/projections')
export class ProjectionAdminController {
    constructor(private readonly readiness: ProjectionReadinessService) {}

    @Get()
    @ApiOperation({summary: 'projection stream별 checkpoint/rebuild 상태 조회'})
    status() {
        return this.readiness.getDetailedStatus();
    }

    @Post(':stream/rebuild')
    @ApiOperation({summary: 'projection stream in-place rebuild 요청'})
    rebuild(@Param('stream') stream: string, @Body() body: RequestProjectionRebuildDto) {
        return this.readiness.requestRebuild(stream, body.reason);
    }
}
