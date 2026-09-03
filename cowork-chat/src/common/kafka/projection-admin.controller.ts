import { Body, Controller, Get, Param, Post } from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiTags } from '@nestjs/swagger';
import { UserRole } from '../enum/user-role.enum';
import { Roles } from '../guard/roles.decorator';
import { RequestProjectionRebuildDto } from './dto/request-projection-rebuild.dto';
import { ProjectionReadinessService } from './projection-readiness.service';

@ApiTags('Projection Admin')
@ApiHeader({ name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN)', required: true })
@Roles(UserRole.ADMIN)
@Controller('admin/projections')
export class ProjectionAdminController {
    constructor(private readonly readiness: ProjectionReadinessService) {}

    @Get()
    @ApiOperation({ summary: 'projection stream별 checkpoint/rebuild 상태 조회' })
    status() {
        return this.readiness.getDetailedStatus();
    }

    @Post(':stream/rebuild')
    @ApiOperation({ summary: 'projection stream in-place rebuild 요청' })
    rebuild(@Param('stream') stream: string, @Body() body: RequestProjectionRebuildDto) {
        return this.readiness.requestRebuild(stream, body.reason);
    }
}
